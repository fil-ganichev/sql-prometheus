package org.lokrusta.sqlprometheus.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeMap;

/**
 * Runs one SQL statement through the Prometheus JDBC driver.
 * Infinity keeps receiving a JSON array. {@code format=frames} returns Grafana frames for the datasource plugin.
 */
public final class QueryService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC);

    static {
        try {
            Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final String jdbcUrl;

    public QueryService(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public Response query(byte[] body) {
        String sql;
        boolean frames;
        try {
            Request request = readRequest(body);
            frames = request.frames;
            sql = TimeFilter.apply(singleStatement(request.sql), request.from, request.to);
        } catch (QueryRejected e) {
            return new Response(400, errorBody(e.getMessage()));
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            Table table = readTable(rows);
            return new Response(200, frames ? framesJson(table) : rowsJson(table));
        } catch (Exception e) {
            int status = prometheusFailure(e) ? 500 : clientFailure(e) ? 400 : 500;
            return new Response(status, errorBody(message(e)));
        }
    }

    public static byte[] errorBody(String message) {
        ObjectNode node = JSON.createObjectNode();
        node.put("message", message == null ? "Query failed" : message);
        return bytes(node);
    }

    private static Request readRequest(byte[] body) {
        if (body == null || body.length == 0) {
            throw new QueryRejected("Request body must be JSON with a sql string");
        }
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (IOException e) {
            throw new QueryRejected("Request body must be JSON with a sql string");
        }
        JsonNode sql = root.get("sql");
        if (sql == null || !sql.isTextual()) {
            throw new QueryRejected("Request body must be JSON with a sql string");
        }
        JsonNode format = root.get("format");
        boolean frames = format != null && format.isTextual() && "frames".equalsIgnoreCase(format.asText());
        return new Request(sql.asText(), millis(root.get("from")), millis(root.get("to")), frames);
    }

    private static Long millis(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asLong();
        }
        if (node.isTextual() && !node.asText().isBlank()) {
            try {
                return Long.parseLong(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new QueryRejected("from and to must be epoch milliseconds");
            }
        }
        return null;
    }

    static String singleStatement(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new QueryRejected("SQL is required");
        }
        String statement = stripTrailingSemicolons(sql.trim());
        if (statement.isBlank()) {
            throw new QueryRejected("SQL is required");
        }
        if (containsSemicolon(statement)) {
            throw new QueryRejected("Only one SQL statement is allowed");
        }
        return statement;
    }

    private static String stripTrailingSemicolons(String sql) {
        String current = sql;
        while (true) {
            int end = current.length();
            while (end > 0 && Character.isWhitespace(current.charAt(end - 1))) {
                end--;
            }
            if (end == 0 || current.charAt(end - 1) != ';' || quoted(current, end - 1)) {
                return current.substring(0, end).trim();
            }
            current = current.substring(0, end - 1);
        }
    }

    private static boolean containsSemicolon(String sql) {
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == ';' && !quoted(sql, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean quoted(String sql, int index) {
        boolean single = false;
        boolean doub = false;
        for (int i = 0; i < index; i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !doub) {
                if (single && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    i++;
                    continue;
                }
                single = !single;
            } else if (c == '"' && !single) {
                doub = !doub;
            }
        }
        return single || doub;
    }

    private static Table readTable(ResultSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        int count = meta.getColumnCount();
        String[] names = new String[count];
        String[] kinds = new String[count];
        for (int i = 1; i <= count; i++) {
            names[i - 1] = meta.getColumnLabel(i);
            kinds[i - 1] = kind(meta.getColumnType(i), names[i - 1]);
        }
        List<Object[]> lines = new ArrayList<>();
        while (rows.next()) {
            Object[] line = new Object[count];
            for (int i = 1; i <= count; i++) {
                Object value = rows.getObject(i);
                if (value == null || rows.wasNull()) {
                    continue;
                }
                if ("time".equals(kinds[i - 1])) {
                    line[i - 1] = epochMillis(value);
                } else if (value instanceof Number number) {
                    line[i - 1] = number.doubleValue();
                } else {
                    line[i - 1] = value.toString();
                }
            }
            lines.add(line);
        }
        for (int i = 0; i < count; i++) {
            if (!"string".equals(kinds[i])) {
                continue;
            }
            for (Object[] line : lines) {
                if (line[i] instanceof Double) {
                    kinds[i] = "number";
                    break;
                }
            }
        }
        return new Table(names, kinds, lines);
    }

    private static String kind(int sqlType, String name) {
        if (sqlType == Types.TIMESTAMP || sqlType == Types.TIMESTAMP_WITH_TIMEZONE || "timestamp".equalsIgnoreCase(name)) {
            return "time";
        }
        if (sqlType == Types.BIGINT || sqlType == Types.INTEGER || sqlType == Types.SMALLINT
                || sqlType == Types.TINYINT || sqlType == Types.DOUBLE || sqlType == Types.FLOAT
                || sqlType == Types.REAL || sqlType == Types.NUMERIC || sqlType == Types.DECIMAL) {
            return "number";
        }
        return "string";
    }

    private static byte[] rowsJson(Table table) {
        ArrayNode array = JSON.createArrayNode();
        for (Object[] line : table.lines) {
            ObjectNode row = array.addObject();
            for (int i = 0; i < table.names.length; i++) {
                Object value = line[i];
                if (value == null) {
                    row.putNull(table.names[i]);
                } else if ("time".equals(table.kinds[i])) {
                    row.put(table.names[i], ISO.format(Instant.ofEpochMilli((Long) value)));
                } else if (value instanceof Double number) {
                    row.put(table.names[i], number);
                } else {
                    row.put(table.names[i], value.toString());
                }
            }
        }
        return bytes(array);
    }

    private static byte[] framesJson(Table table) {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode frames = root.putArray("frames");
        int time = indexOf(table.kinds, "time");
        if (time < 0) {
            frames.add(tableFrame(table));
        } else {
            for (Series series : series(table, time)) {
                frames.add(seriesFrame(series));
            }
        }
        return bytes(root);
    }

    private static ObjectNode tableFrame(Table table) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode schema = frame.putObject("schema");
        ArrayNode fields = schema.putArray("fields");
        ArrayNode columns = frame.putObject("data").putArray("values");
        for (int i = 0; i < table.names.length; i++) {
            ObjectNode field = fields.addObject();
            field.put("name", table.names[i]);
            field.put("type", table.kinds[i]);
            ArrayNode column = columns.addArray();
            for (Object[] line : table.lines) {
                addValue(column, line[i]);
            }
        }
        return frame;
    }

    private static List<Series> series(Table table, int time) {
        List<Integer> labels = indexes(table.kinds, "string");
        List<Integer> numbers = indexes(table.kinds, "number");
        TreeMap<String, Series> grouped = new TreeMap<>();
        for (Object[] line : table.lines) {
            String name = seriesName(line, labels, numbers.isEmpty() ? "value" : table.names[numbers.get(0)]);
            Series series = grouped.computeIfAbsent(name, Series::new);
            series.time.add(line[time]);
            series.values.add(numbers.isEmpty() ? null : line[numbers.get(0)]);
        }
        if (grouped.isEmpty()) {
            Series empty = new Series(numbers.isEmpty() ? "value" : table.names[numbers.get(0)]);
            grouped.put(empty.name, empty);
        }
        return new ArrayList<>(grouped.values());
    }

    private static String seriesName(Object[] line, List<Integer> labels, String fallback) {
        StringBuilder name = new StringBuilder();
        for (int label : labels) {
            Object value = line[label];
            if (value == null || value.toString().isBlank()) {
                continue;
            }
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(value);
        }
        return name.isEmpty() ? fallback : name.toString();
    }

    private static ObjectNode seriesFrame(Series series) {
        ObjectNode frame = JSON.createObjectNode();
        ObjectNode schema = frame.putObject("schema");
        schema.put("name", series.name);
        ArrayNode fields = schema.putArray("fields");
        ObjectNode time = fields.addObject();
        time.put("name", "Time");
        time.put("type", "time");
        ObjectNode value = fields.addObject();
        // Grafana uses the frame name as the legend when the value field is called Value.
        value.put("name", "Value");
        value.put("type", "number");
        ObjectNode data = frame.putObject("data");
        ArrayNode columns = data.putArray("values");
        ArrayNode times = columns.addArray();
        ArrayNode values = columns.addArray();
        for (int i = 0; i < series.time.size(); i++) {
            addValue(times, series.time.get(i));
            addValue(values, series.values.get(i));
        }
        return frame;
    }

    private static void addValue(ArrayNode column, Object value) {
        if (value == null) {
            column.addNull();
        } else if (value instanceof Double number) {
            if (Double.isFinite(number)) {
                column.add(number);
            } else {
                column.addNull();
            }
        } else if (value instanceof Long number) {
            column.add(number);
        } else {
            column.add(value.toString());
        }
    }

    private static int indexOf(String[] kinds, String kind) {
        for (int i = 0; i < kinds.length; i++) {
            if (kind.equals(kinds[i])) {
                return i;
            }
        }
        return -1;
    }

    private static List<Integer> indexes(String[] kinds, String kind) {
        List<Integer> found = new ArrayList<>();
        for (int i = 0; i < kinds.length; i++) {
            if (kind.equals(kinds[i])) {
                found.add(i);
            }
        }
        return found;
    }

    private static long epochMillis(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof Timestamp stamp) {
            // Calcite fills java.sql.Timestamp from the UTC fields using the JVM zone.
            return stamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        if (value instanceof Date date) {
            return date.getTime();
        }
        if (value instanceof Instant instant) {
            return instant.toEpochMilli();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant().toEpochMilli();
        }
        if (value instanceof LocalDateTime local) {
            return local.toInstant(ZoneOffset.UTC).toEpochMilli();
        }
        throw new IllegalStateException("Unsupported timestamp value: " + value.getClass().getName());
    }

    private static boolean prometheusFailure(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String text = current.getMessage();
            if (text != null && text.contains("Prometheus")) {
                return true;
            }
        }
        return false;
    }

    private static boolean clientFailure(Throwable error) {
        if (error instanceof SQLException || error instanceof QueryRejected) {
            return true;
        }
        for (Throwable current = error; current != null; current = current.getCause()) {
            for (StackTraceElement frame : current.getStackTrace()) {
                String type = frame.getClassName();
                if (type.startsWith("org.lokrusta.sqlprometheus.")
                        && !type.startsWith("org.lokrusta.sqlprometheus.grafana.")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String message(Throwable error) {
        String fallback = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        String prometheus = null;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains("Prometheus")) {
                prometheus = current.getMessage();
            }
        }
        return prometheus == null ? fallback : prometheus;
    }

    private static byte[] bytes(JsonNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write JSON", e);
        }
    }

    public record Response(int status, byte[] body) {
    }

    private record Request(String sql, Long from, Long to, boolean frames) {
    }

    private record Table(String[] names, String[] kinds, List<Object[]> lines) {
    }

    private static final class Series {
        private final String name;
        private final List<Object> time = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();

        private Series(String name) {
            this.name = name;
        }
    }
}

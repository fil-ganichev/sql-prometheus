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
import java.util.Date;

/**
 * Runs one SQL statement through the Prometheus JDBC driver and writes Infinity JSON.
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
        try {
            sql = singleStatement(readSql(body));
        } catch (QueryRejected e) {
            return new Response(400, errorBody(e.getMessage()));
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            return new Response(200, rowsJson(rows));
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

    private static String readSql(byte[] body) {
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
        return sql.asText();
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

    private static byte[] rowsJson(ResultSet rows) throws SQLException {
        ResultSetMetaData meta = rows.getMetaData();
        int columns = meta.getColumnCount();
        ArrayNode array = JSON.createArrayNode();
        while (rows.next()) {
            ObjectNode row = array.addObject();
            for (int i = 1; i <= columns; i++) {
                String label = meta.getColumnLabel(i);
                Object value = rows.getObject(i);
                if (value == null || rows.wasNull()) {
                    row.putNull(label);
                    continue;
                }
                int type = meta.getColumnType(i);
                if (type == Types.TIMESTAMP || type == Types.TIMESTAMP_WITH_TIMEZONE) {
                    row.put(label, timestamp(value));
                } else if (value instanceof Number number) {
                    row.put(label, number.doubleValue());
                } else {
                    row.put(label, value.toString());
                }
            }
        }
        return bytes(array);
    }

    private static String timestamp(Object value) {
        long millis;
        if (value instanceof Number number) {
            millis = number.longValue();
        } else if (value instanceof Timestamp stamp) {
            // Calcite fills java.sql.Timestamp from the UTC fields using the JVM zone.
            millis = stamp.toLocalDateTime().toInstant(ZoneOffset.UTC).toEpochMilli();
        } else if (value instanceof Date date) {
            millis = date.getTime();
        } else if (value instanceof Instant instant) {
            millis = instant.toEpochMilli();
        } else if (value instanceof OffsetDateTime offset) {
            millis = offset.toInstant().toEpochMilli();
        } else if (value instanceof LocalDateTime local) {
            millis = local.toInstant(ZoneOffset.UTC).toEpochMilli();
        } else {
            throw new IllegalStateException("Unsupported timestamp value: " + value.getClass().getName());
        }
        return ISO.format(Instant.ofEpochMilli(millis));
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

    private static final class QueryRejected extends RuntimeException {
        private QueryRejected(String message) {
            super(message);
        }
    }
}

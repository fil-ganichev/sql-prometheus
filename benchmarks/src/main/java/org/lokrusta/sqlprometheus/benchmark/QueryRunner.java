package org.lokrusta.sqlprometheus.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class QueryRunner {
    public static final String BASE_URL = "http://localhost:9090";
    public static final String STEP = "15s";
    // The recorded span is several days. The driver default of 1000 rows would abort that range,
    // and the default 30s timeout is tight for a full query_range of it.
    public static final String JDBC_URL =
            "jdbc:prometheus://localhost:9090?step=15s&maxRows=100000&queryTimeout=120";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Comparator<Point> ORDER =
            Comparator.comparingLong(Point::timestamp).thenComparingDouble(Point::value);

    private QueryRunner() {
    }

    public static void waitUntilReady() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        URI ready = URI.create(BASE_URL + "/-/ready");
        long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpResponse<Void> response = client.send(
                        HttpRequest.newBuilder(ready).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception ignored) {
                // Prometheus is still replaying the WAL.
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("Prometheus did not become ready at " + ready);
    }

    public static int drainSql(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int count = 0;
            while (rows.next()) {
                rows.getLong(1);
                rows.getDouble(2);
                count++;
            }
            return count;
        }
    }

    public static int drainPromql(HttpClient client, String promql, RecordedWindow window) throws Exception {
        return readPromql(client, promql, window).size();
    }

    public static void verify(Connection connection, HttpClient client, RecordedWindow window) throws Exception {
        for (QueryPair pair : QueryPair.ALL) {
            List<Point> sql = readSql(connection, pair.sql(window));
            List<Point> promql = readPromql(client, pair.promql(), window);
            if (sql.isEmpty() || promql.isEmpty()) {
                throw new IllegalStateException(pair.name() + " returned no points (sql="
                        + sql.size() + ", promql=" + promql.size() + ")");
            }
            sql.sort(ORDER);
            promql.sort(ORDER);
            if (sql.size() != promql.size()) {
                throw new IllegalStateException(pair.name() + " point count differs: sql="
                        + sql.size() + ", promql=" + promql.size());
            }
            for (int i = 0; i < sql.size(); i++) {
                Point left = sql.get(i);
                Point right = promql.get(i);
                if (left.timestamp != right.timestamp || Double.compare(left.value, right.value) != 0) {
                    throw new IllegalStateException(pair.name() + " differs at " + i
                            + ": sql=" + left + ", promql=" + right);
                }
            }
            System.out.println(pair.name() + ": " + sql.size() + " matching points");
        }
    }

    private static List<Point> readSql(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            List<Point> points = new ArrayList<>();
            while (rows.next()) {
                // getObject shifts TIMESTAMP by the JVM zone. The driver stores unix millis.
                points.add(new Point(rows.getLong(1), rows.getDouble(2)));
            }
            return points;
        }
    }

    private static List<Point> readPromql(HttpClient client, String promql, RecordedWindow window) throws Exception {
        String url = BASE_URL + "/api/v1/query_range?query="
                + URLEncoder.encode(promql, StandardCharsets.UTF_8)
                + "&start=" + seconds(window.startMillis())
                + "&end=" + seconds(window.endMillis())
                + "&step=" + STEP;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Prometheus returned " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = MAPPER.readTree(response.body());
        if (!"success".equals(root.path("status").asText())) {
            throw new IllegalStateException("Prometheus error: " + root.path("error").asText(response.body()));
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return List.of();
        }
        if (result.size() != 1) {
            throw new IllegalStateException(promql + " returned " + result.size() + " series");
        }
        List<Point> points = new ArrayList<>();
        for (JsonNode sample : result.get(0).path("values")) {
            long timestamp = Math.round(sample.get(0).asDouble() * 1000.0);
            if (timestamp < window.startMillis() || timestamp > window.endMillis()) {
                continue;
            }
            points.add(new Point(timestamp, Double.parseDouble(sample.get(1).asText())));
        }
        return points;
    }

    private static String seconds(long millis) {
        return String.format(Locale.US, "%.3f", millis / 1000.0);
    }

    private record Point(long timestamp, double value) {
    }
}

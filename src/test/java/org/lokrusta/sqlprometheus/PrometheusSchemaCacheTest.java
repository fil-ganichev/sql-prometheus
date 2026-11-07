package org.lokrusta.sqlprometheus;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.lokrusta.sqlprometheus.exec.SeriesMetadata;
import org.lokrusta.sqlprometheus.schema.PrometheusSchema;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusSchemaCacheTest {
    @Test
    void loadsAMetricOnFirstUseAndCachesAMissingName() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        Map<String, AtomicInteger> series = new ConcurrentHashMap<>();
        HttpServer server = server(series, metric -> {
            if ("http_requests_total".equals(metric)) {
                return seriesJson(metric, "\"operation\":\"Get\",\"service\":\"api\"");
            }
            if ("http_request_duration_seconds_bucket".equals(metric)) {
                return seriesJson(metric, "\"le\":\"0.1\",\"service_name\":\"api\"");
            }
            return "{\"status\":\"success\",\"data\":[]}";
        });
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort();
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery(
                        "SELECT operation, service, \"value\" FROM http_requests_total")) {
                    assertTrue(rows.next());
                    assertEquals("Get", rows.getString("operation"));
                    assertEquals("api", rows.getString("service"));
                    assertEquals(1.0, rows.getDouble("value"));
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT operation FROM http_requests_total")) {
                    assertTrue(rows.next());
                }
                assertEquals(1, series.get("http_requests_total").get());

                assertThrows(SQLException.class, () -> statement.executeQuery(
                        "SELECT \"value\" FROM missing_metric_total").close());
                assertThrows(SQLException.class, () -> statement.executeQuery(
                        "SELECT \"value\" FROM missing_metric_total").close());
                assertEquals(1, series.get("missing_metric_total").get());

                try (ResultSet rows = statement.executeQuery(
                        "SELECT le FROM http_request_duration_seconds_bucket")) {
                    ResultSetMetaData meta = rows.getMetaData();
                    int le = rows.findColumn("le");
                    assertEquals(Types.DOUBLE, meta.getColumnType(le));
                    assertTrue(rows.next());
                    assertEquals(0.1, rows.getDouble("le"));
                }
                assertEquals(1, series.get("http_request_duration_seconds_bucket").get());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheTtlComesFromTheDriverUrl() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        Map<String, AtomicInteger> series = new ConcurrentHashMap<>();
        HttpServer server = server(series, metric -> seriesJson(metric, "\"operation\":\"Get\""));
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort()
                    + "?schemaCacheTtlSeconds=1";
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT operation FROM http_requests_total").close();
                Thread.sleep(1500);
                statement.executeQuery("SELECT operation FROM http_requests_total").close();
            }
            assertEquals(2, series.get("http_requests_total").get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cacheByteLimitComesFromTheDriverUrl() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        SeriesMetadata labels = new SeriesMetadata(true, java.util.List.of("abcdefghij"));
        int oneMetric = PrometheusSchema.weight("metric_alpha", labels);
        Map<String, AtomicInteger> series = new ConcurrentHashMap<>();
        HttpServer server = server(series, metric -> seriesJson(metric, "\"abcdefghij\":\"x\""));
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort()
                    + "?schemaCacheMaxBytes=" + oneMetric;
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT abcdefghij FROM metric_alpha").close();
                statement.executeQuery("SELECT abcdefghij FROM metric_gamma").close();
                statement.executeQuery("SELECT abcdefghij FROM metric_alpha").close();
            }
            assertEquals(2, series.get("metric_alpha").get());
            assertEquals(1, series.get("metric_gamma").get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void driverRejectsNonPositiveCacheSettings() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        SQLException bytes = assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:9?schemaCacheMaxBytes=0"));
        assertTrue(bytes.getMessage().contains("schemaCacheMaxBytes"), bytes.getMessage());
        SQLException ttl = assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:9?schemaCacheTtlSeconds=0"));
        assertTrue(ttl.getMessage().contains("schemaCacheTtlSeconds"), ttl.getMessage());
    }

    @Test
    void prometheusErrorsAreNotCached() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/series", exchange -> {
            int attempt = calls.incrementAndGet();
            byte[] bytes = attempt == 1
                    ? "{\"status\":\"error\",\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8)
                    : seriesJson("http_requests_total", "\"operation\":\"Get\"").getBytes(StandardCharsets.UTF_8);
            if (attempt == 1) {
                exchange.sendResponseHeaders(500, bytes.length);
            } else {
                exchange.sendResponseHeaders(200, bytes.length);
            }
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/query_range", exchange -> respond(exchange, matrix("operation", "Get")));
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort();
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                assertThrows(SQLException.class, () -> statement.executeQuery(
                        "SELECT operation FROM http_requests_total").close());
                try (ResultSet rows = statement.executeQuery("SELECT operation FROM http_requests_total")) {
                    assertTrue(rows.next());
                    assertEquals("Get", rows.getString("operation"));
                }
            }
            assertEquals(2, calls.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer server(Map<String, AtomicInteger> series, java.util.function.Function<String, String> body)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/series", exchange -> {
            String metric = metric(exchange);
            series.computeIfAbsent(metric, name -> new AtomicInteger()).incrementAndGet();
            respond(exchange, body.apply(metric));
        });
        server.createContext("/api/v1/query_range", exchange -> respond(exchange, matrix("operation", "Get")));
        return server;
    }

    private static String metric(HttpExchange exchange) {
        String raw = exchange.getRequestURI().getRawQuery();
        String decoded = raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
        int separator = decoded.indexOf('=');
        return separator >= 0 ? decoded.substring(separator + 1) : decoded;
    }

    private static String seriesJson(String metric, String labels) {
        return "{\"status\":\"success\",\"data\":[{\"__name__\":\"" + metric + "\"," + labels + "}]}";
    }

    private static String matrix(String label, String value) {
        long now = System.currentTimeMillis() / 1000;
        return "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"" + label + "\":\"" + value + "\",\"service\":\"api\",\"abcdefghij\":\"x\",\"le\":\"0.1\"},"
                + "\"values\":[[" + now + ",\"1\"]]}]}}";
    }

    private static void respond(HttpExchange exchange, String json) throws java.io.IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}

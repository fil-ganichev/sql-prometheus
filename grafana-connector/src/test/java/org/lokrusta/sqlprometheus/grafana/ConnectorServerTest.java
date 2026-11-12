package org.lokrusta.sqlprometheus.grafana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectorServerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SAMPLE_TIME = "1594671549";
    private static final String SQL = """
            SELECT "timestamp", "value", instance, le
            FROM http_request_duration_seconds_bucket
            WHERE "timestamp" >= TIMESTAMP '2020-07-13 00:00:00'
              AND "timestamp" < TIMESTAMP '2020-07-14 00:00:00'
            """;

    private HttpServer prometheus;
    private ExecutorService prometheusWorkers;
    private ConnectorServer connector;
    private final CopyOnWriteArrayList<String> rangeQueries = new CopyOnWriteArrayList<>();
    private final AtomicInteger prometheusCalls = new AtomicInteger();
    private volatile int rangeStatus = 200;
    private volatile CountDownLatch rangeEntered;
    private volatile CountDownLatch rangeRelease;

    @BeforeEach
    void start() throws Exception {
        prometheus = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        prometheus.createContext("/api/v1/series", exchange -> {
            prometheusCalls.incrementAndGet();
            byte[] body = """
                    {"status":"success","data":[{"__name__":"http_request_duration_seconds_bucket","instance":"a:9100","le":"1"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        prometheus.createContext("/api/v1/query_range", exchange -> {
            prometheusCalls.incrementAndGet();
            rangeQueries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            if (rangeEntered != null) {
                rangeEntered.countDown();
                try {
                    rangeRelease.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = rangeStatus == 200
                    ? ("""
                    {"status":"success","data":{"resultType":"matrix","result":[{"metric":{"instance":"a:9100"},"values":[[%s,"0.12"]]}]}}
                    """.formatted(SAMPLE_TIME)).getBytes(StandardCharsets.UTF_8)
                    : "unavailable".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(rangeStatus, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        prometheusWorkers = Executors.newFixedThreadPool(4);
        prometheus.setExecutor(prometheusWorkers);
        prometheus.start();
        String jdbc = "jdbc:prometheus://localhost:" + prometheus.getAddress().getPort();
        connector = ConnectorServer.start(0, jdbc);
    }

    @AfterEach
    void stop() {
        if (connector != null) {
            connector.close();
        }
        if (prometheus != null) {
            prometheus.stop(0);
        }
        if (prometheusWorkers != null) {
            prometheusWorkers.shutdownNow();
        }
    }

    @Test
    void threadCountMustBePositive() {
        assertThrows(IllegalArgumentException.class,
                () -> ConnectorServer.start(0, "jdbc:prometheus://localhost:9", 0));
    }

    @Test
    void twoQueriesRunAtTheSameTime() throws Exception {
        rangeEntered = new CountDownLatch(2);
        rangeRelease = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            String body = "{\"sql\":" + JSON.writeValueAsString(SQL) + "}";
            Future<HttpResponse<String>> first = callers.submit(() -> send("POST", "/query", body));
            Future<HttpResponse<String>> second = callers.submit(() -> send("POST", "/query", body));
            assertTrue(rangeEntered.await(5, TimeUnit.SECONDS));
            rangeRelease.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(200, second.get(5, TimeUnit.SECONDS).statusCode());
        } finally {
            rangeRelease.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> response = send("GET", "/health", null);
        assertEquals(200, response.statusCode());
        assertEquals("up", JSON.readTree(response.body()).get("status").asText());
    }

    @Test
    void queryReturnsInfinityRowsAndKeepsTheSqlTimeRange() throws Exception {
        HttpResponse<String> response = send("POST", "/query", "{\"sql\":" + JSON.writeValueAsString(SQL + ";") + "}");
        assertEquals(200, response.statusCode(), response.body());
        JsonNode row = JSON.readTree(response.body()).get(0);
        assertEquals("2020-07-13T20:19:09.000Z", row.get("timestamp").asText());
        assertEquals(0.12d, row.get("value").asDouble(), 0.0000001d);
        assertEquals("a:9100", row.get("instance").asText());
        assertTrue(row.get("le").isNull());
        assertEquals(1, rangeQueries.size());
        long start = querySeconds(rangeQueries.get(0), "start");
        long end = querySeconds(rangeQueries.get(0), "end");
        assertTrue(start < 1_594_671_549L && end > 1_594_671_549L, rangeQueries.get(0));
        assertTrue(start > 1_577_836_800L, rangeQueries.get(0));
    }

    private static long querySeconds(String raw, String name) {
        int at = raw.indexOf(name + "=");
        int amp = raw.indexOf('&', at);
        String value = amp < 0 ? raw.substring(at + name.length() + 1) : raw.substring(at + name.length() + 1, amp);
        return (long) Double.parseDouble(value);
    }

    @Test
    void framesNameTheSeriesAfterLabelsAndKeepInfinityRows() throws Exception {
        String payload = "{\"sql\":" + JSON.writeValueAsString(SQL) + ",\"format\":\"frames\"}";
        HttpResponse<String> frames = send("POST", "/query", payload);
        assertEquals(200, frames.statusCode(), frames.body());
        JsonNode frame = JSON.readTree(frames.body()).get("frames").get(0);
        assertEquals("a:9100", frame.get("schema").get("name").asText());
        assertEquals("time", frame.get("schema").get("fields").get(0).get("type").asText());
        assertEquals("Value", frame.get("schema").get("fields").get(1).get("name").asText());
        assertEquals(1_594_671_549_000L, frame.get("data").get("values").get(0).get(0).asLong());
        assertEquals(0.12d, frame.get("data").get("values").get(1).get(0).asDouble(), 0.0000001d);

        HttpResponse<String> rows = send("POST", "/query", "{\"sql\":" + JSON.writeValueAsString(SQL) + "}");
        assertTrue(JSON.readTree(rows.body()).isArray(), rows.body());
    }

    @Test
    void timeFilterUsesTheDashboardRange() throws Exception {
        String sql = """
                SELECT "timestamp", "value"
                FROM http_request_duration_seconds_bucket
                WHERE $__timeFilter("timestamp")
                """;
        String payload = "{\"sql\":" + JSON.writeValueAsString(sql)
                + ",\"from\":1594598400000,\"to\":1594684800000}";
        HttpResponse<String> response = send("POST", "/query", payload);
        assertEquals(200, response.statusCode(), response.body());
        long start = querySeconds(rangeQueries.get(0), "start");
        long end = querySeconds(rangeQueries.get(0), "end");
        assertTrue(start >= 1_594_598_400L - 1 && start <= 1_594_598_400L + 5, rangeQueries.get(0));
        assertTrue(end >= 1_594_684_800L - 5 && end <= 1_594_684_800L + 1, rangeQueries.get(0));
    }

    @Test
    void timeFilterWithoutRangeIsRejectedBeforePrometheus() throws Exception {
        String payload = "{\"sql\":\"SELECT \\\"value\\\" FROM http_request_duration_seconds_bucket WHERE $__timeFilter(\\\"timestamp\\\")\"}";
        HttpResponse<String> response = send("POST", "/query", payload);
        assertEquals(400, response.statusCode(), response.body());
        assertEquals("$__timeFilter requires from and to", JSON.readTree(response.body()).get("message").asText());
        assertEquals(0, prometheusCalls.get());
    }

    @Test
    void browserPreflightIsAllowed() throws Exception {
        HttpResponse<String> response = send("OPTIONS", "/query", null);
        assertEquals(204, response.statusCode());
        assertEquals("*", response.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
    }

    @Test
    void labelFilterFromSqlReachesPrometheus() throws Exception {
        String sql = """
                SELECT "timestamp", "value"
                FROM http_request_duration_seconds_bucket
                WHERE instance = 'a:9100'
                  AND "timestamp" >= TIMESTAMP '2020-07-13 00:00:00'
                  AND "timestamp" < TIMESTAMP '2020-07-14 00:00:00'
                """;
        HttpResponse<String> response = send("POST", "/query", "{\"sql\":" + JSON.writeValueAsString(sql) + "}");
        assertEquals(200, response.statusCode(), response.body());
        assertTrue(rangeQueries.get(0).contains("instance=\"a:9100\""), rangeQueries.get(0));
    }

    @Test
    void emptyAndMultipleStatementsAreRejectedBeforePrometheus() throws Exception {
        HttpResponse<String> empty = send("POST", "/query", "{\"sql\":\"   \"}");
        assertEquals(400, empty.statusCode());
        assertEquals("SQL is required", JSON.readTree(empty.body()).get("message").asText());

        HttpResponse<String> several = send("POST", "/query",
                "{\"sql\":\"SELECT \\\"value\\\" FROM http_request_duration_seconds_bucket; SELECT \\\"value\\\" FROM http_request_duration_seconds_bucket\"}");
        assertEquals(400, several.statusCode());
        assertEquals("Only one SQL statement is allowed", JSON.readTree(several.body()).get("message").asText());
        assertEquals(0, prometheusCalls.get());
    }

    @Test
    void malformedSqlIsAClientError() throws Exception {
        HttpResponse<String> response = send("POST", "/query", "{\"sql\":\"SELECT\"}");
        assertEquals(400, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).get("message").asText().length() > 0);
    }

    @Test
    void prometheusFailureIsAServerError() throws Exception {
        rangeStatus = 500;
        HttpResponse<String> response = send("POST", "/query", "{\"sql\":" + JSON.writeValueAsString(SQL) + "}");
        assertEquals(500, response.statusCode(), response.body());
        assertTrue(JSON.readTree(response.body()).get("message").asText().contains("Prometheus"), response.body());
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + connector.port() + path));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}

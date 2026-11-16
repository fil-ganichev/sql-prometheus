package org.lokrusta.sqlprometheus;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrometheusPlanCacheTest {
    @Test
    void repeatedExecuteQueryReusesThePlanAndStillReadsPrometheus() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        AtomicInteger queries = new AtomicInteger();
        HttpServer server = server(queries);
        server.start();
        try {
            String url = url(server) + "?planCacheMaxBytes=1048576";
            String sql = "SELECT operation FROM http_requests_total";
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                read(statement, sql);
                read(statement, sql);
                try (Statement other = connection.createStatement();
                     ResultSet first = statement.executeQuery(sql);
                     ResultSet second = other.executeQuery(sql)) {
                    assertTrue(first.next());
                    assertTrue(second.next());
                    assertEquals("Get", first.getString("operation"));
                    assertEquals("Get", second.getString("operation"));
                }
            }
            assertEquals(4, queries.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void prepareStatementUsesTheSameCache() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        AtomicInteger queries = new AtomicInteger();
        HttpServer server = server(queries);
        server.start();
        try {
            String url = url(server) + "?planCacheMaxBytes=1048576";
            String sql = "SELECT operation FROM http_requests_total";
            try (Connection connection = DriverManager.getConnection(url)) {
                try (PreparedStatement prepared = connection.prepareStatement(sql);
                     ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                }
                try (PreparedStatement prepared = connection.prepareStatement(sql);
                     ResultSet rows = prepared.executeQuery()) {
                    assertTrue(rows.next());
                    assertEquals("Get", rows.getString("operation"));
                }
            }
            assertEquals(2, queries.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void zeroBytesLeavesPlanningUncachedAndNegativeBytesIsRejected() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        SQLException negative = assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:9?planCacheMaxBytes=-1"));
        assertTrue(negative.getMessage().contains("planCacheMaxBytes"), negative.getMessage());
        AtomicInteger queries = new AtomicInteger();
        HttpServer server = server(queries);
        server.start();
        try {
            try (Connection connection = DriverManager.getConnection(url(server) + "?planCacheMaxBytes=0");
                 Statement statement = connection.createStatement()) {
                read(statement, "SELECT operation FROM http_requests_total");
                read(statement, "SELECT operation FROM http_requests_total");
            }
            assertEquals(2, queries.get());
        } finally {
            server.stop(0);
        }
    }

    private static void read(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next());
            assertEquals("Get", rows.getString("operation"));
        }
    }

    private static String url(HttpServer server) {
        return "jdbc:prometheus://localhost:" + server.getAddress().getPort();
    }

    private static HttpServer server(AtomicInteger queries) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/series", exchange -> {
            byte[] body = "{\"status\":\"success\",\"data\":[{\"__name__\":\"http_requests_total\",\"operation\":\"Get\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/v1/query_range", exchange -> {
            queries.incrementAndGet();
            long now = System.currentTimeMillis() / 1000 - 60;
            byte[] body = ("{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                    + "{\"metric\":{\"operation\":\"Get\"},\"values\":[[" + now + ",\"1\"]]}]}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        return server;
    }
}

package org.lokrusta.sqlprometheus;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.junit.jupiter.api.Test;
import org.lokrusta.sqlprometheus.driver.PrometheusParserFactory;
import org.lokrusta.sqlprometheus.exec.PrometheusRowIterator;
import org.lokrusta.sqlprometheus.plan.PrometheusPrograms;
import org.lokrusta.sqlprometheus.schema.PrometheusSchema;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.sun.net.httpserver.HttpServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PrometheusDriverTest {
    static final String CPU_SHARE_SQL = """
            SELECT
                c.image,
                (SUM(c.value) / SUM(n.value)) * 100 AS cpu_percentage
            FROM container_cpu_usage_seconds_total c
            JOIN node_cpu_seconds_total n ON c.instance = n.instance
            WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
              AND c.image IS NOT NULL
            GROUP BY c.image
            """;

    @Test
    void averagePushesModeFilterIntoScan() throws Exception {
        String sql = "SELECT AVG(\"value\") FROM node_cpu_seconds_total WHERE mode = 'idle'";
        RelNode before = logicalPlan(sql);
        String afterPlan = pushedPlan(before);

        assertTrue(RelOptUtil.toString(before).contains("LogicalFilter"), RelOptUtil.toString(before));
        assertTrue(afterPlan.contains("PrometheusTableScan"), afterPlan);
        assertTrue(afterPlan.contains("idle"), afterPlan);
        assertFalse(afterPlan.contains("LogicalFilter"), afterPlan);
    }

    @Test
    void closedWindowReachesBothScans() throws Exception {
        String sql = """
                SELECT c.image, (SUM(c.value) / SUM(n.value)) * 100 AS cpu_percentage
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > TIMESTAMP '2026-10-23 18:00:00'
                  AND c.timestamp < TIMESTAMP '2026-10-23 18:05:00'
                  AND c.image IS NOT NULL
                GROUP BY c.image
                """;
        String after = pushedPlan(logicalPlan(sql));
        assertEquals(2, count(after, "PrometheusTableScan"), after);
        assertEquals(2, count(after, "start=["), after);
        assertEquals(2, count(after, "end=["), after);
        assertFalse(after.contains("LogicalFilter"), after);
        String node = pushedPlan(logicalPlan("""
                SELECT n.instance, n.value
                FROM node_cpu_seconds_total n
                WHERE n.timestamp > TIMESTAMP '2026-10-23 18:00:00'
                  AND n.timestamp < TIMESTAMP '2026-10-23 18:05:00'
                """));
        assertTrue(node.contains("start=["), node);
        assertTrue(node.contains("end=["), node);
        assertFalse(node.contains("LogicalFilter"), node);
    }

    @Test
    void unboundedScanHitsRowCap() {
        StringBuilder values = new StringBuilder();
        int samples = PrometheusRowIterator.DEFAULT_MAX_ROWS + 1;
        for (int i = 0; i < samples; i++) {
            if (i > 0) {
                values.append(',');
            }
            values.append('[').append(i).append(",\"1\"]");
        }
        String json = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{},\"values\":[" + values + "]}]}}";
        PrometheusRowIterator rows = PrometheusRowIterator.fromJson(json, java.util.List.of(), 0, Long.MAX_VALUE);
        try {
            while (rows.hasNext()) {
                rows.next();
            }
            fail("row cap was not enforced");
        } catch (RuntimeException thrown) {
            assertTrue(thrown.getMessage().contains("превышен лимит строк"), thrown.getMessage());
        }
    }

    @Test
    void maxRowsParameterComesFromUrl() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"localhost\",\"mode\":\"idle\"},\"values\":["
                + "[" + (now - 30) + ",\"1\"],[" + (now - 15) + ",\"1\"],[" + now + ",\"1\"]"
                + "]}]}}";
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            SQLException rejected = assertThrows(SQLException.class, () ->
                    DriverManager.getConnection("jdbc:prometheus://localhost:" + port + "?maxRows=0"));
            assertTrue(rejected.getMessage().contains("maxRows"), rejected.getMessage());

            String url = "jdbc:prometheus://localhost:" + port + "?maxRows=2";
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery(
                         "SELECT \"value\" FROM node_cpu_seconds_total")) {
                int[] seen = {0};
                RuntimeException thrown = assertThrows(RuntimeException.class, () -> {
                    while (resultSet.next()) {
                        seen[0]++;
                    }
                });
                assertTrue(seen[0] >= 1 && seen[0] <= 2, thrown.getMessage());
                assertTrue(thrown.getMessage().contains("(2)"), thrown.getMessage());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void querySettingsComeFromUrl() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        int port = 9;
        assertTrue(assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:" + port + "?step=0"))
                .getMessage().contains("step"));
        assertTrue(assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:" + port + "?connectTimeout=0"))
                .getMessage().contains("connectTimeout"));
        assertTrue(assertThrows(SQLException.class, () ->
                DriverManager.getConnection("jdbc:prometheus://localhost:" + port + "?queryTimeout=0"))
                .getMessage().contains("queryTimeout"));

        long now = System.currentTimeMillis() / 1000;
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"localhost\",\"mode\":\"idle\"},\"values\":[[" + now + ",\"1\"]]}]}}";
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            query.set(rawQuery);
            if (rawQuery != null && rawQuery.contains("step=1s")) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            int livePort = server.getAddress().getPort();
            String url = "jdbc:prometheus://localhost:" + livePort
                    + "?step=30s&connectTimeout=5&queryTimeout=20";
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT \"value\" FROM node_cpu_seconds_total").close();
            }
            assertTrue(query.get().contains("step=30s"), query.get());

            String slow = "jdbc:prometheus://localhost:" + livePort + "?step=1s&queryTimeout=1";
            SQLException timedOut = assertThrows(SQLException.class, () -> {
                try (Connection connection = DriverManager.getConnection(slow);
                     Statement statement = connection.createStatement()) {
                    statement.executeQuery("SELECT \"value\" FROM node_cpu_seconds_total").close();
                }
            });
            assertTrue(timeoutMessage(timedOut).contains("timed out"), timedOut.toString());
        } finally {
            server.stop(0);
        }
    }

    private static String timeoutMessage(Throwable thrown) {
        StringBuilder message = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            if (current.getMessage() != null) {
                message.append(current.getMessage());
            }
            current = current.getCause();
        }
        return message.toString();
    }

    @Test
    void timeFilterReachesBothScans() throws Exception {
        String afterPlan = pushedPlan(logicalPlan(CPU_SHARE_SQL));
        System.out.println(afterPlan);
        assertEquals(2, count(afterPlan, "PrometheusTableScan"), afterPlan);
        assertEquals(2, count(afterPlan, "start=["), afterPlan);
        assertTrue(afterPlan.contains("container_cpu_usage_seconds_total"), afterPlan);
        assertTrue(afterPlan.contains("node_cpu_seconds_total"), afterPlan);
        assertFalse(afterPlan.contains("CURRENT_TIMESTAMP"), afterPlan);
    }

    static String pushedPlan(RelNode before) {
        HepPlanner hepPlanner = new HepPlanner(PrometheusPrograms.hepProgram());
        hepPlanner.setRoot(before);
        return RelOptUtil.toString(hepPlanner.findBestExp());
    }

    static RelNode logicalPlan(String sql) throws RelConversionException, ValidationException, org.apache.calcite.sql.parser.SqlParseException {
        SchemaPlus root = Frameworks.createRootSchema(true);
        SchemaPlus prometheus = root.add("prometheus", new PrometheusSchema("http://localhost"));
        Planner planner = Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .defaultSchema(prometheus)
                .operatorTable(SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(
                        SqlLibrary.STANDARD, SqlLibrary.MYSQL))
                .parserConfig(SqlParser.config()
                        .withParserFactory(PrometheusParserFactory.INSTANCE)
                        .withCaseSensitive(false)
                        .withUnquotedCasing(Casing.UNCHANGED)
                        .withQuotedCasing(Casing.UNCHANGED))
                .build());
        SqlNode parsed = planner.parse(sql);
        SqlNode validated = planner.validate(parsed);
        return planner.rel(validated).rel;
    }

    private static int count(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int found = text.indexOf(token, from);
            if (found < 0) {
                return count;
            }
            count++;
            from = found + token.length();
        }
    }
}

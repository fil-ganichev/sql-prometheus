package org.lokrusta.sqlprometheus;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
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
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
        serveBuiltinSeries(server);
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
        serveBuiltinSeries(server);
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

    private static void serveBuiltinSeries(HttpServer server) {
        server.createContext("/api/v1/series", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            String decoded = raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
            int separator = decoded.indexOf('=');
            String metric = separator >= 0 ? decoded.substring(separator + 1) : decoded;
            String json = builtinSeriesJson(metric);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }

    private static String builtinSeriesJson(String metric) {
        String labels = switch (metric) {
            case "node_cpu_seconds_total" -> "\"instance\":\"localhost\",\"mode\":\"idle\"";
            case "container_cpu_usage_seconds_total" -> "\"instance\":\"localhost\",\"image\":\"app\"";
            case "kube_pod_info" -> "\"node\":\"n\",\"pod\":\"p\"";
            case "http_request_duration_seconds_bucket" ->
                    "\"instance\":\"localhost\",\"le\":\"0.1\",\"service_name\":\"api\"";
            default -> null;
        };
        if (labels == null) {
            return "{\"status\":\"success\",\"data\":[]}";
        }
        return "{\"status\":\"success\",\"data\":[{\"__name__\":\"" + metric + "\"," + labels + "}]}";
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
    void functionKeepsTimeFilter() throws Exception {
        String sql = """
                SELECT PROM_RATE("value", '5m')
                FROM node_cpu_seconds_total
                WHERE mode = 'idle'
                  AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                """;
        String plan = pushedPlan(logicalPlan(sql));
        assertTrue(plan.contains("function=[rate]"), plan);
        assertTrue(plan.contains("window=[5m]"), plan);
        assertTrue(plan.contains("start=["), plan);
        assertTrue(plan.contains("mode=idle"), plan);
        assertFalse(plan.contains("PROM_RATE"), plan);

        String summed = pushedPlan(logicalPlan("""
                SELECT SUM(PROM_RATE("value", '1m'))
                FROM node_cpu_seconds_total
                WHERE mode = 'idle'
                  AND "timestamp" > NOW() - INTERVAL '5' MINUTE
                """));
        assertTrue(summed.contains("function=[rate]"), summed);
        assertTrue(summed.contains("window=[1m]"), summed);
        assertTrue(summed.contains("start=["), summed);
        assertFalse(summed.contains("PROM_RATE"), summed);
        assertFalse(summed.contains("agg=["), summed);
    }

    @Test
    void flatJoinPushesFunctionIntoItsScan() throws Exception {
        String sql = """
                SELECT
                    c.image,
                    AVG(PROM_RATE(c.value, '5m')) AS avg_rate,
                    AVG(n.value) AS avg_node
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
                  AND c.image IS NOT NULL
                GROUP BY c.image
                """;
        RelNode before = logicalPlan(sql);
        String beforePlan = RelOptUtil.toString(before);
        String afterPlan = pushedPlan(before);
        System.out.println("flat join before optimizations");
        System.out.println(beforePlan);
        System.out.println("flat join after optimizations");
        System.out.println(afterPlan);

        assertTrue(beforePlan.contains("PROM_RATE($1, '5m')"), beforePlan);
        assertFalse(beforePlan.contains("function=["), beforePlan);
        assertEquals(2, count(afterPlan, "PrometheusTableScan"), afterPlan);
        assertEquals(2, count(afterPlan, "start=["), afterPlan);
        assertEquals(1, count(afterPlan, "function=[rate]"), afterPlan);
        assertEquals(1, count(afterPlan, "window=[5m]"), afterPlan);
        assertTrue(afterPlan.contains("avg_rate=[AVG($1)]"), afterPlan);
        assertTrue(afterPlan.contains("avg_node=[AVG($2)]"), afterPlan);
        assertFalse(afterPlan.contains("PROM_RATE"), afterPlan);
        int container = afterPlan.indexOf("container_cpu_usage_seconds_total");
        int node = afterPlan.indexOf("node_cpu_seconds_total");
        assertTrue(container >= 0 && node > container, afterPlan);
        assertTrue(afterPlan.substring(container, node).contains("function=[rate]"), afterPlan);
        assertFalse(afterPlan.substring(node).contains("function="), afterPlan);
    }

    @Test
    void flatJoinPushesEachSideFunctionIntoItsScan() throws Exception {
        String sql = """
                SELECT
                    c.image,
                    AVG(PROM_RATE(c.value, '5m')) AS avg_rate,
                    AVG(PROM_IRATE(n.value, '1m')) AS avg_node
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.image
                """;
        RelNode before = logicalPlan(sql);
        String beforePlan = RelOptUtil.toString(before);
        String afterPlan = pushedPlan(before);
        System.out.println("flat join both functions before optimizations");
        System.out.println(beforePlan);
        System.out.println("flat join both functions after optimizations");
        System.out.println(afterPlan);

        assertTrue(beforePlan.contains("PROM_RATE($1, '5m')"), beforePlan);
        assertTrue(beforePlan.contains("PROM_IRATE($5, '1m')"), beforePlan);
        assertFalse(afterPlan.contains("PROM_RATE"), afterPlan);
        assertFalse(afterPlan.contains("PROM_IRATE"), afterPlan);
        int container = afterPlan.indexOf("container_cpu_usage_seconds_total");
        int node = afterPlan.indexOf("node_cpu_seconds_total");
        assertTrue(container >= 0 && node > container, afterPlan);
        String containerScan = afterPlan.substring(container, node);
        String nodeScan = afterPlan.substring(node);
        assertTrue(containerScan.contains("function=[rate]"), afterPlan);
        assertTrue(containerScan.contains("window=[5m]"), afterPlan);
        assertTrue(nodeScan.contains("function=[irate]"), afterPlan);
        assertTrue(nodeScan.contains("window=[1m]"), afterPlan);
        assertEquals(2, count(afterPlan, "start=["), afterPlan);
    }

    @Test
    void percentileAndRoundOnOneMetric() throws Exception {
        String sql = """
                SELECT service_name,
                       ROUND(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le)) AS p95
                FROM http_request_duration_seconds_bucket
                WHERE "timestamp" > NOW() - INTERVAL '5' MINUTE
                GROUP BY service_name
                """;
        RelNode before = logicalPlan(sql);
        String beforePlan = RelOptUtil.toString(before);
        String afterPlan = pushedPlan(before);
        System.out.println("percentile one metric before optimizations");
        System.out.println(beforePlan);
        System.out.println("percentile one metric after optimizations");
        System.out.println(afterPlan);

        assertTrue(beforePlan.contains("PERCENTILE_CONT"), beforePlan);
        assertTrue(beforePlan.contains("ROUND"), beforePlan);
        assertFalse(beforePlan.contains("quantile=["), beforePlan);
        assertTrue(afterPlan.contains("quantile=[0.95]"), afterPlan);
        assertTrue(afterPlan.contains("sumBy=[le, service_name]"), afterPlan);
        assertTrue(afterPlan.contains("range=[5m]"), afterPlan);
        assertTrue(afterPlan.contains("scalars=[round]"), afterPlan);
        assertFalse(afterPlan.contains("PERCENTILE_CONT"), afterPlan);
        assertFalse(afterPlan.contains("ROUND"), afterPlan);

        List<String> queries = prometheusQueries(sql);
        assertEquals(1, queries.size(), queries.toString());
        assertTrue(queries.get(0).contains(
                "round(histogram_quantile(0.95, sum by (le, service_name) (rate(http_request_duration_seconds_bucket[5m]))))"),
                queries.get(0));
    }

    @Test
    void percentileAndAbsOnFlatJoin() throws Exception {
        String sql = """
                SELECT c.service_name,
                       ABS(PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.le)) AS p95,
                       AVG(n.value) AS avg_node
                FROM http_request_duration_seconds_bucket c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.service_name
                """;
        RelNode before = logicalPlan(sql);
        String beforePlan = RelOptUtil.toString(before);
        String afterPlan = pushedPlan(before);
        System.out.println("percentile flat join before optimizations");
        System.out.println(beforePlan);
        System.out.println("percentile flat join after optimizations");
        System.out.println(afterPlan);

        assertTrue(beforePlan.contains("PERCENTILE_CONT"), beforePlan);
        assertTrue(beforePlan.contains("ABS"), beforePlan);
        assertTrue(beforePlan.contains("LogicalJoin"), beforePlan);
        assertFalse(beforePlan.contains("quantile=["), beforePlan);
        assertTrue(afterPlan.contains("LogicalJoin"), afterPlan);
        assertTrue(afterPlan.contains("AVG"), afterPlan);
        assertFalse(afterPlan.contains("PERCENTILE_CONT"), afterPlan);
        assertFalse(afterPlan.contains("ABS("), afterPlan);
        int bucket = afterPlan.indexOf("http_request_duration_seconds_bucket");
        int node = afterPlan.indexOf("node_cpu_seconds_total");
        assertTrue(bucket >= 0 && node > bucket, afterPlan);
        String bucketScan = afterPlan.substring(bucket, node);
        assertTrue(bucketScan.contains("quantile=[0.95]"), afterPlan);
        assertTrue(bucketScan.contains("scalars=[abs]"), afterPlan);
        assertTrue(bucketScan.contains("sumBy=[le, service_name, instance]"), afterPlan);
        assertTrue(bucketScan.contains("range=[5m]"), afterPlan);
        assertFalse(afterPlan.substring(node).contains("quantile="), afterPlan);

        List<String> queries = prometheusQueries(sql);
        assertTrue(queries.stream().anyMatch(query -> query.contains(
                "abs(histogram_quantile(0.95, sum by (le, service_name, instance) (rate(http_request_duration_seconds_bucket[5m]))))")),
                queries.toString());
        assertTrue(queries.stream().anyMatch(query -> query.contains("node_cpu_seconds_total") && !query.contains("histogram_quantile")),
                queries.toString());
    }

    @Test
    void percentileCommaJoinWhereEqualityReachesSumBy() throws Exception {
        String sql = """
                SELECT c.service_name,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.le) AS p95
                FROM http_request_duration_seconds_bucket c, node_cpu_seconds_total n
                WHERE c.instance = n.instance
                  AND c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.service_name
                """;
        String afterPlan = pushedPlan(logicalPlan(sql));
        assertTrue(afterPlan.contains("joinType=[inner]"), afterPlan);
        assertTrue(afterPlan.contains("quantile=[0.95]"), afterPlan);
        assertTrue(afterPlan.contains("sumBy=[le, service_name, instance]"), afterPlan);
        assertTrue(afterPlan.contains("range=[5m]"), afterPlan);
        assertFalse(afterPlan.contains("PERCENTILE_CONT"), afterPlan);

        List<String> queries = prometheusQueries(sql, false);
        assertTrue(queries.stream().anyMatch(query -> query.contains(
                "histogram_quantile(0.95, sum by (le, service_name, instance) (rate(http_request_duration_seconds_bucket[5m])))")),
                queries.toString());
    }

    @Test
    void percentileComparisonWithoutReturnedLabelFails() throws Exception {
        IllegalStateException where = assertThrows(IllegalStateException.class, () -> pushedPlan(logicalPlan("""
                SELECT c.service_name,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.le) AS p95
                FROM http_request_duration_seconds_bucket c, node_cpu_seconds_total n
                WHERE c.instance > n.instance
                  AND c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.service_name
                """)));
        assertTrue(where.getMessage().contains("instance"), where.getMessage());
        assertTrue(where.getMessage().contains(">"), where.getMessage());

        IllegalStateException onJoin = assertThrows(IllegalStateException.class, () -> pushedPlan(logicalPlan("""
                SELECT c.service_name,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.le) AS p95
                FROM http_request_duration_seconds_bucket c
                JOIN node_cpu_seconds_total n ON c.instance >= n.instance
                WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.service_name
                """)));
        assertTrue(onJoin.getMessage().contains("instance"), onJoin.getMessage());
        assertTrue(onJoin.getMessage().contains(">="), onJoin.getMessage());

        IllegalStateException bucket = assertThrows(IllegalStateException.class, () -> pushedPlan(logicalPlan("""
                SELECT service_name,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM http_request_duration_seconds_bucket
                WHERE le > 1
                  AND "timestamp" > NOW() - INTERVAL '5' MINUTE
                GROUP BY service_name
                """)));
        assertTrue(bucket.getMessage().contains("le"), bucket.getMessage());
        assertTrue(bucket.getMessage().contains(">"), bucket.getMessage());

        String grouped = pushedPlan(logicalPlan("""
                SELECT c.service_name,
                       c.instance,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.le) AS p95
                FROM http_request_duration_seconds_bucket c, node_cpu_seconds_total n
                WHERE c.instance > n.instance
                  AND c.timestamp > NOW() - INTERVAL '5' MINUTE
                GROUP BY c.service_name, c.instance
                """));
        assertTrue(grouped.contains("sumBy=[le, service_name, instance]"), grouped);
        assertTrue(grouped.contains("quantile=[0.95]"), grouped);
    }

    @Test
    void prometheusAggregateKeepsLabelsNeededAfterTheCall() throws Exception {
        String joined = pushedPlan(logicalPlan("""
                SELECT c.image, PROM_SUM(PROM_RATE(c."value", '5m'))
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > NOW() - INTERVAL '5' MINUTE
                """));
        assertTrue(joined.contains("function=[rate]"), joined);
        assertTrue(joined.contains("window=[5m]"), joined);
        assertTrue(joined.contains("agg=[sum]"), joined);
        assertTrue(joined.contains("aggBy=[instance, image]"), joined);
        assertTrue(joined.contains("start=["), joined);
        assertFalse(joined.contains("PROM_SUM"), joined);
        int container = joined.indexOf("container_cpu_usage_seconds_total");
        int node = joined.indexOf("node_cpu_seconds_total");
        assertTrue(container >= 0 && node > container, joined);
        assertFalse(joined.substring(node).contains("agg="), joined);

        List<String> queries = prometheusQueries("""
                SELECT c.image, PROM_SUM(PROM_RATE(c."value", '5m'))
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > TIMESTAMP '2026-01-01 00:00:00'
                """, false);
        assertTrue(queries.stream().anyMatch(query -> query.contains(
                "sum by (image, instance) (rate(container_cpu_usage_seconds_total[5m]))")), queries.toString());

        String outside = pushedPlan(logicalPlan("""
                SELECT ABS(PROM_SUM(PROM_RATE("value", '5m')))
                FROM node_cpu_seconds_total
                WHERE mode = 'idle'
                """));
        assertTrue(outside.contains("scalars=[abs]"), outside);
        assertTrue(outside.contains("agg=[sum]"), outside);
        assertTrue(outside.contains("function=[rate]"), outside);
        assertFalse(outside.contains("aggBy="), outside);

        String inside = pushedPlan(logicalPlan("""
                SELECT PROM_SUM(ROUND(PROM_RATE("value", '5m')))
                FROM node_cpu_seconds_total
                WHERE mode = 'idle'
                """));
        assertTrue(inside.contains("inner=[round]"), inside);
        assertTrue(inside.contains("agg=[sum]"), inside);
        assertFalse(inside.contains("aggBy="), inside);

        String averaged = pushedPlan(logicalPlan("""
                SELECT instance, PROM_AVG("value")
                FROM node_cpu_seconds_total
                """));
        assertTrue(averaged.contains("agg=[avg]"), averaged);
        assertTrue(averaged.contains("aggBy=[instance]"), averaged);
        assertFalse(averaged.contains("function="), averaged);

        assertThrows(ValidationException.class, () -> logicalPlan("""
                SELECT PROM_HOLT_WINTERS("value", '5m', 1.5, 0.1)
                FROM node_cpu_seconds_total
                """));
        assertThrows(IllegalStateException.class, () -> pushedPlan(logicalPlan("""
                SELECT PROM_SUM(PROM_AVG("value"))
                FROM node_cpu_seconds_total
                """)));
    }

    private static List<String> prometheusQueries(String sql) throws Exception {
        return prometheusQueries(sql, true);
    }

    private static List<String> prometheusQueries(String sql, boolean singleRow) throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"service_name\":\"api\",\"instance\":\"localhost\",\"mode\":\"idle\"},\"values\":[["
                + (now - 60) + ",\"0.10\"],[" + now + ",\"0.42\"]]}]}}";
        List<String> queries = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            queries.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        serveBuiltinSeries(server);
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort();
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(sql)) {
                assertTrue(rows.next(), queries.toString());
                if (singleRow) {
                    assertFalse(rows.next(), queries.toString());
                }
            }
            return queries;
        } finally {
            server.stop(0);
        }
    }

    @Test
    void functionsReachPrometheusQuery() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        long now = System.currentTimeMillis() / 1000;
        String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                + "{\"metric\":{\"instance\":\"localhost\",\"mode\":\"idle\"},\"values\":[[" + now + ",\"1\"]]}]}}";
        AtomicReference<String> query = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        serveBuiltinSeries(server);
        server.start();
        try {
            String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort();
            String[][] functions = {
                    {"PROM_RATE", "rate"},
                    {"PROM_IRATE", "irate"},
                    {"PROM_INCREASE", "increase"},
                    {"PROM_DELTA", "delta"},
                    {"PROM_IDELTA", "idelta"},
                    {"PROM_CHANGES", "changes"},
                    {"PROM_DERIV", "deriv"},
                    {"PROM_RESET", "resets"},
                    {"PROM_MAD", "mad_over_time"}
            };
            try (Connection connection = DriverManager.getConnection(url);
                 Statement statement = connection.createStatement()) {
                for (String[] function : functions) {
                    statement.executeQuery("""
                            SELECT %s("value", '5m')
                            FROM node_cpu_seconds_total
                            WHERE mode = 'idle'
                              AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                            """.formatted(function[0])).close();
                    String raw = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
                    assertTrue(raw.contains(function[1] + "(node_cpu_seconds_total{mode=\"idle\"}[5m])"), raw);
                }
                statement.executeQuery("""
                        SELECT SUM(PROM_RATE("value", '1m'))
                        FROM node_cpu_seconds_total
                        WHERE mode = 'idle'
                          AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                        """).close();
                String summed = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
                assertTrue(summed.contains("rate(node_cpu_seconds_total{mode=\"idle\"}[1m])"), summed);
                assertFalse(summed.contains("sum("), summed);

                statement.executeQuery("""
                        SELECT PROM_PREDICT_LINEAR("value", '5m', 3600)
                        FROM node_cpu_seconds_total
                        WHERE mode = 'idle'
                          AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                        """).close();
                String predicted = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
                assertTrue(predicted.contains("predict_linear(node_cpu_seconds_total{mode=\"idle\"}[5m], 3600)"), predicted);

                statement.executeQuery("""
                        SELECT PROM_HOLT_WINTERS("value", '5m', 0.5, 0.1)
                        FROM node_cpu_seconds_total
                        WHERE mode = 'idle'
                          AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                        """).close();
                String smoothed = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
                assertTrue(smoothed.contains("holt_winters(node_cpu_seconds_total{mode=\"idle\"}[5m], 0.5, 0.1)"), smoothed);

                statement.executeQuery("""
                        SELECT PROM_SUM(PROM_RATE("value", '5m'))
                        FROM node_cpu_seconds_total
                        WHERE mode = 'idle'
                          AND "timestamp" > TIMESTAMP '2026-01-01 00:00:00'
                        """).close();
                String aggregated = URLDecoder.decode(query.get(), StandardCharsets.UTF_8);
                assertTrue(aggregated.contains("sum(rate(node_cpu_seconds_total{mode=\"idle\"}[5m]))"), aggregated);
                assertTrue(aggregated.contains("start="), aggregated);
            }
        } finally {
            server.stop(0);
        }
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
        SchemaPlus prometheus = root.add("prometheus", PrometheusSchema.withoutFunctions("http://localhost"));
        Planner planner = Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .defaultSchema(prometheus)
                .operatorTable(PrometheusSqlOperatorTable.INSTANCE)
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

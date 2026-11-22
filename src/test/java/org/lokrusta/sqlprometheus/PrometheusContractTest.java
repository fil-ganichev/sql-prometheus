package org.lokrusta.sqlprometheus;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lokrusta.sqlprometheus.driver.PrometheusParserFactory;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;
import org.lokrusta.sqlprometheus.plan.PrometheusPrograms;
import org.lokrusta.sqlprometheus.schema.PrometheusTable;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Договор из {@code .tmpdoc/refactoring/test-queries.md}.
 * План каждого кейса сравнивается с {@code src/test/resources/contract/<id>.plan.txt}.
 */
@Tag("contract")
class PrometheusContractTest {
    private static final String WINDOW = """
              AND "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
              AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
            """;

    private static HttpServer server;
    private static final List<String> QUERIES = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/api/v1/query_range", exchange -> {
            QUERIES.add(URLDecoder.decode(exchange.getRequestURI().getRawQuery(), StandardCharsets.UTF_8));
            String raw = exchange.getRequestURI().getRawQuery();
            String decoded = raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
            long start = parameter(decoded, "start");
            String body = "{\"status\":\"success\",\"data\":{\"resultType\":\"matrix\",\"result\":["
                    + "{\"metric\":{\"process_action\":\"getOrder\",\"code\":\"SUCCESS\",\"le\":\"1\"},"
                    + "\"values\":[[" + (start + 1) + ",\"2.5\"]]}]}}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/v1/series", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            String decoded = raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
            int separator = decoded.indexOf('=');
            String metric = separator >= 0 ? decoded.substring(separator + 1) : "";
            String labels = metric.contains("bucket")
                    ? "\"process_action\":\"getOrder\",\"code\":\"SUCCESS\",\"le\":\"1\""
                    : "\"process_action\":\"getOrder\",\"code\":\"SUCCESS\"";
            String json = "{\"status\":\"success\",\"data\":[{\"__name__\":\"" + metric + "\"," + labels + "}]}";
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void contractQueries() throws Exception {
        String url = "jdbc:prometheus://localhost:" + server.getAddress().getPort() + "?step=15s&maxRows=100000";
        succeeds(url, "1", """
                SELECT PROM_RATE("value", '5m')
                FROM operation_code_total
                WHERE "value" > 1
                """ + WINDOW, "rate((operation_code_total > 1)[5m:15s])");
        succeeds(url, "2", """
                SELECT PROM_RATE_WHEN("value" > 1, '5m', '1m')
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "rate((operation_code_total > 1)[5m:1m])");
        succeeds(url, "3", """
                SELECT *
                FROM (
                    SELECT PROM_RATE("value", '5m') AS v
                    FROM operation_code_total
                    WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                      AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                ) t
                WHERE v > 1
                """, "rate(operation_code_total[5m])");
        succeeds(url, "4", """
                SELECT PROM_RATE("value", '5m')
                FROM operation_code_total
                WHERE code = 'ERROR'
                """ + WINDOW, "rate(operation_code_total{code=\"ERROR\"}[5m])");
        succeeds(url, "5", """
                SELECT PROM_RATE("value", '5m')
                FROM operation_code_total
                WHERE code <> 'ERROR'
                """ + WINDOW, "rate(operation_code_total{code!=\"ERROR\"}[5m])");
        fails(url, "6", """
                SELECT PROM_RATE("value", '5m')
                FROM operation_code_total
                WHERE code = 'ERROR' OR process_action = 'getOrder'
                """);
        fails(url, "7", """
                SELECT PROM_MIN("value"), PROM_MAX("value")
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """);
        succeeds(url, "8", """
                SELECT "timestamp", PROM_SUM(PROM_RATE("value", '5m'))
                FROM operation_code_total
                WHERE code = 'ERROR'
                """ + WINDOW, "sum(rate(operation_code_total{code=\"ERROR\"}[5m]))");
        succeeds(url, "9", """
                SELECT MIN("value"), MAX("value")
                FROM operation_code_total
                WHERE code <> 'ERROR'
                """ + WINDOW, "operation_code_total{code!=\"ERROR\"}");
        succeeds(url, "10a", """
                SELECT ABS(PROM_RATE("value", '5m'))
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "abs(rate(operation_code_total[5m]))");
        succeeds(url, "10b", """
                SELECT ABS("value"), LN("value")
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "operation_code_total");
        succeeds(url, "11a", """
                SELECT PROM_RATE("value", '5m', '1m')
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "rate(operation_code_total[5m:1m])");
        succeeds(url, "11b", """
                SELECT PROM_RATE("value", '5m')
                FROM operation_code_total
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "rate(operation_code_total[5m])");
        succeeds(url, "12", """
                SELECT code,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > NOW() - INTERVAL '15' MINUTE
                GROUP BY code
                """, "histogram_quantile(0.95, sum by (le, code) (rate(operation_time_seconds_bucket[15m])))");
        fails(url, "12b", """
                SELECT PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM operation_time_seconds_bucket
                WHERE le > 1
                  AND "timestamp" > NOW() - INTERVAL '15' MINUTE
                """);
        succeeds(url, "12c", """
                SELECT code,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                GROUP BY code
                """, "histogram_quantile(0.95", "[1h]", "step=1h");
        succeeds(url, "12d", """
                SELECT code,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 04:00:00'
                GROUP BY code, FLOOR("timestamp" TO HOUR)
                """, "histogram_quantile(0.95", "[1h]", "step=1h");
        succeeds(url, "12e", """
                SELECT PROM_HISTOGRAM_QUANTILE(0.95, '5m')
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "histogram_quantile(0.95", "[5m]", "step=15s");
        String ansi = """
                SELECT c."timestamp", c.process_action, t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                WHERE c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """;
        String comma = """
                SELECT c."timestamp", c.process_action, t."value"
                FROM operation_code_total c, operation_time_seconds_count t
                WHERE c.process_action = t.process_action
                  AND c.code = t.code
                  AND c."timestamp" = t."timestamp"
                  AND c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """;
        ContractPlans.assertSame("13", pushed(ansi));
        ContractPlans.assertSame("13", pushed(comma));
        succeeds(url, "13", ansi, "operation_code_total", "operation_time_seconds_count");
        shows(url, "13c", """
                SELECT c."timestamp", c.process_action, t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c.code = 'ERROR'
                 AND c."timestamp" = t."timestamp"
                WHERE c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """, "code=\"ERROR\"");
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.executeQuery("""
                    EXPLAIN PLAN FOR
                    SELECT "value"
                    FROM operation_code_total
                    WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                      AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                    """).close();
        } catch (SQLException ex) {
            fail("explain " + causes(ex));
        }
        fails(url, "13b", """
                SELECT c."timestamp", t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                WHERE c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """);
        succeeds(url, "14", """
                SELECT c."timestamp", c.process_action
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                JOIN operation_time_seconds_bucket b
                  ON b.process_action = c.process_action
                 AND b.code = c.code
                 AND b."timestamp" = c."timestamp"
                WHERE c.code = 'SUCCESS'
                  AND c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 00:15:00'
                """, "code=\"SUCCESS\"");
        succeeds(url, "15", """
                SELECT r.process_action, r.v, t."value"
                FROM (
                    SELECT process_action, code, "timestamp", PROM_RATE("value", '5m') AS v
                    FROM operation_code_total
                    WHERE code = 'SUCCESS'
                      AND "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                      AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                ) r
                JOIN operation_time_seconds_count t
                  ON t.process_action = r.process_action
                 AND t.code = r.code
                 AND t."timestamp" = r."timestamp"
                """, "rate(operation_code_total{code=\"SUCCESS\"}[5m])");
        succeeds(url, "16", """
                SELECT c."timestamp", c.process_action, t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                WHERE c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                  AND (c.code = 'SUCCESS' OR c.process_action = 'getOrder')
                  AND t."value" > 0
                """, "operation_code_total&");
        succeeds(url, "17", """
                SELECT c."timestamp", c.process_action
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                JOIN operation_time_seconds_bucket b
                  ON b.process_action = c.process_action
                 AND b.code = c.code
                 AND b."timestamp" = c."timestamp"
                WHERE c.code = 'SUCCESS'
                  AND (c.process_action = 'getOrder' OR c.process_action = 'createOrder')
                  AND b."value" > 0
                  AND c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 00:15:00'
                """, "code=\"SUCCESS\"");
        succeeds(url, "18a", """
                SELECT PROM_AVG_OVER_TIME("value", '5m')
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                  AND process_action = 'getOrder'
                  AND "value" > 1
                """ + WINDOW, "avg_over_time((operation_code_total{", "> 1)[5m:15s])", "code=\"SUCCESS\"", "process_action=\"getOrder\"");
        succeeds(url, "18b", """
                SELECT PROM_QUANTILE_OVER_TIME("value", '5m', 0.95)
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                """ + WINDOW, "quantile_over_time(0.95, operation_code_total{code=\"SUCCESS\"}[5m])");
        fails(url, "18c", """
                SELECT PROM_MAX_OVER_TIME("value", '5m')
                FROM operation_code_total
                WHERE (code = 'SUCCESS' OR process_action = 'getOrder')
                """ + WINDOW);
        fails(url, "18d", """
                SELECT PROM_SUM_OVER_TIME("value", '5m')
                FROM operation_code_total
                WHERE ("value" > 1 OR "value" < 0)
                """ + WINDOW);
        succeeds(url, "19a", """
                SELECT PROM_IRATE("value", '5m')
                FROM operation_code_total
                WHERE code <> 'ERROR'
                """ + WINDOW, "irate(operation_code_total{code!=\"ERROR\"}[5m])");
        succeeds(url, "19b", """
                SELECT ABS(PROM_SUM("value"))
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                  AND process_action = 'getOrder'
                """ + WINDOW, "abs(sum(operation_code_total{", "code=\"SUCCESS\"", "process_action=\"getOrder\"");
        fails(url, "19c", """
                SELECT PROM_IRATE("value", '5m')
                FROM operation_code_total
                WHERE process_action LIKE 'get%'
                """ + WINDOW);
        fails(url, "19d", """
                SELECT PROM_SUM("value")
                FROM operation_code_total
                WHERE (code = 'SUCCESS' OR process_action = 'getOrder')
                """ + WINDOW);
        succeeds(url, "20a", """
                SELECT AVG("value")
                FROM operation_code_total
                WHERE code <> 'ERROR'
                  AND "value" > 1
                """ + WINDOW, "operation_code_total{code!=\"ERROR\"}");
        succeeds(url, "20b", """
                SELECT AVG("value")
                FROM operation_code_total
                WHERE (code = 'SUCCESS' OR process_action = 'getOrder')
                  AND "value" > 1
                """ + WINDOW, "operation_code_total&");
        succeeds(url, "20c", """
                SELECT ABS("value")
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                """ + WINDOW, "operation_code_total{code=\"SUCCESS\"}");
        fails(url, "20d", """
                SELECT SUM(PROM_SUM("value"))
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                """ + WINDOW);
        succeeds(url, "21", """
                SELECT PROM_RATE(c."value", '5m'), t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                WHERE c.code = 'SUCCESS'
                  AND t."value" > 0
                """ + "  AND c.\"timestamp\" > TIMESTAMP '2026-11-18 00:00:00'\n"
                + "  AND c.\"timestamp\" <= TIMESTAMP '2026-11-18 01:00:00'\n",
                "rate(operation_code_total{code=\"SUCCESS\"}[5m])");
        fails(url, "22", """
                SELECT PROM_RATE(c."value", '5m'), t."value"
                FROM operation_code_total c
                JOIN operation_time_seconds_count t
                  ON c.process_action = t.process_action
                 AND c.code = t.code
                 AND c."timestamp" = t."timestamp"
                WHERE t.code = 'ERROR'
                  AND c."timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND c."timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                """);
        shows(url, "23", """
                SELECT "timestamp", "value"
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                  AND process_action = 'getOrder'
                """ + WINDOW, "code=\"SUCCESS\"", "process_action=\"getOrder\"");
        shows(url, "24", """
                SELECT "timestamp", "value"
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                  AND "value" > 1
                """ + WINDOW, "operation_code_total{code=\"SUCCESS\"}");
        shows(url, "25", """
                SELECT "timestamp", "value"
                FROM operation_code_total
                WHERE code = 'SUCCESS'
                  AND (process_action = 'getOrder' OR process_action = 'createOrder')
                """ + WINDOW, "operation_code_total{code=\"SUCCESS\"}");
        shows(url, "26", """
                SELECT "timestamp", "value"
                FROM operation_code_total
                WHERE (code = 'ERROR'
                    OR process_action = 'getOrder'
                   AND code = 'SUCCESS')
                """ + WINDOW, "operation_code_total&");
        succeeds(url, "29", """
                SELECT code,
                       FLOOR("timestamp" TO HOUR) AS hour,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY le) AS p95
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 04:00:00'
                GROUP BY code, FLOOR("timestamp" TO HOUR)
                """, "histogram_quantile(0.95", "[1h]", "step=1h");
        succeeds(url, "30", """
                SELECT code,
                       PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY "value") AS p95
                FROM operation_time_seconds_bucket
                WHERE "timestamp" > TIMESTAMP '2026-11-18 00:00:00'
                  AND "timestamp" <= TIMESTAMP '2026-11-18 01:00:00'
                GROUP BY code
                """, "operation_time_seconds_bucket");
    }

    private static void succeeds(String url, String id, String sql, String... fragments) throws Exception {
        ContractPlans.assertSame(id, pushed(sql));
        List<String> queries = execute(url, sql);
        System.out.println("case " + id + " promql " + queries);
        String text = String.join("\n", queries);
        for (String fragment : fragments) {
            assertTrue(text.contains(fragment), id + " missing " + fragment + " in " + text);
        }
        if ("11b".equals(id) || "3".equals(id)) {
            assertFalse(text.contains("[5m:"), text);
        }
        if ("10b".equals(id) || "20c".equals(id)) {
            assertFalse(text.contains("abs("), text);
            assertFalse(text.contains("ln("), text);
        }
        if ("29".equals(id)) {
            assertEquals(1, countRows(url, sql));
        }
        if ("30".equals(id)) {
            assertFalse(text.contains("histogram_quantile"), text);
            assertEquals(2.5, firstDouble(url, sql, "p95"), 0.0001);
        }
        if ("9".equals(id) || "20a".equals(id) || "20b".equals(id)) {
            assertFalse(text.contains("min_over_time"), text);
            assertFalse(text.contains("avg_over_time"), text);
            assertFalse(text.contains("min("), text);
        }
        if ("16".equals(id) || "20b".equals(id)) {
            assertFalse(text.contains("code="), text);
        }
    }

    private static void shows(String url, String id, String sql, String... queryFragments) throws Exception {
        ContractPlans.assertSame(id, pushed(sql));
        List<String> queries = execute(url, sql);
        System.out.println("case " + id + " promql " + queries);
        String text = String.join("\n", queries);
        for (String fragment : queryFragments) {
            assertTrue(text.contains(fragment), id + " missing " + fragment + " in " + text);
        }
        if ("24".equals(id) || "25".equals(id)) {
            assertFalse(text.contains(">"), text);
            assertFalse(text.contains("getOrder"), text);
            assertFalse(text.contains("createOrder"), text);
        }
        if ("26".equals(id)) {
            assertFalse(text.contains("code="), text);
            assertFalse(text.contains("process_action"), text);
        }
    }

    private static void fails(String url, String id, String sql) throws Exception {
        String plan;
        try {
            plan = pushed(sql);
        } catch (RuntimeException ex) {
            plan = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        }
        ContractPlans.assertSame(id, plan);
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.executeQuery(sql).close();
            fail(id + " executed: " + QUERIES);
        } catch (SQLException ex) {
            System.out.println("case " + id + " error\n" + causes(ex));
        }
    }

    private static int countRows(String url, String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            int count = 0;
            while (rows.next()) {
                count++;
            }
            return count;
        }
    }

    private static double firstDouble(String url, String sql, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertTrue(rows.next(), sql);
            return rows.getDouble(column);
        }
    }

    private static List<String> execute(String url, String sql) throws Exception {
        QUERIES.clear();
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            statement.executeQuery(sql).close();
        }
        return List.copyOf(QUERIES);
    }

    private static String pushed(String sql) throws Exception {
        SchemaPlus root = Frameworks.createRootSchema(true);
        PrometheusOptions options = PrometheusOptions.defaults();
        SchemaPlus prometheus = root.add("prometheus", new AbstractSchema() {
            @Override
            protected Map<String, Table> getTableMap() {
                return Map.of(
                        "operation_code_total", PrometheusTable.metric(
                                "http://localhost", "operation_code_total", List.of("process_action", "code"), options),
                        "operation_time_seconds_count", PrometheusTable.metric(
                                "http://localhost", "operation_time_seconds_count", List.of("process_action", "code"), options),
                        "operation_time_seconds_bucket", PrometheusTable.metric(
                                "http://localhost", "operation_time_seconds_bucket",
                                List.of("process_action", "code", "le"), options));
            }
        });
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
        RelNode logical = planner.rel(validated).rel;
        HepPlanner hep = new HepPlanner(PrometheusPrograms.hepProgram());
        hep.setRoot(logical);
        return RelOptUtil.toString(hep.findBestExp());
    }

    private static String causes(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                text.append(current.getMessage()).append('\n');
            }
        }
        return text.toString();
    }

    private static long parameter(String query, String name) {
        String marker = name + "=";
        int start = query.indexOf(marker);
        if (start < 0) {
            return 0;
        }
        int value = start + marker.length();
        int end = query.indexOf('&', value);
        String text = end < 0 ? query.substring(value) : query.substring(value, end);
        int dot = text.indexOf('.');
        if (dot >= 0) {
            text = text.substring(0, dot);
        }
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}

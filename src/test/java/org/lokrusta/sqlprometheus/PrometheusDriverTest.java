package org.lokrusta.sqlprometheus;

import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.hep.HepPlanner;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.schema.SchemaPlus;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParser;
import org.lokrusta.sqlprometheus.driver.PrometheusParserFactory;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.Planner;
import org.apache.calcite.tools.RelConversionException;
import org.apache.calcite.tools.ValidationException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lokrusta.sqlprometheus.plan.PrometheusPrograms;
import org.lokrusta.sqlprometheus.schema.PrometheusSchema;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PrometheusDriverTest {
    private static final String URL = "jdbc:calcite-prometheus:"
            + "schemaFactory=org.lokrusta.sqlprometheus.schema.PrometheusSchemaFactory;"
            + "schema=prometheus;"
            + "unquotedCasing=unchanged";

    @BeforeAll
    static void loadDriver() throws ClassNotFoundException {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
    }

    @Test
    void averagePushesModeFilterIntoScan() throws Exception {
        String sql = "SELECT AVG(\"value\") FROM node_cpu_seconds_total WHERE mode = 'idle'";
        RelNode before = logicalPlan(sql);
        System.out.println("BEFORE:\n" + RelOptUtil.toString(before));

        HepPlanner hepPlanner = new HepPlanner(PrometheusPrograms.hepProgram());
        hepPlanner.setRoot(before);
        RelNode after = hepPlanner.findBestExp();
        String afterPlan = RelOptUtil.toString(after);
        System.out.println("AFTER:\n" + afterPlan);

        assertTrue(RelOptUtil.toString(before).contains("LogicalFilter"), RelOptUtil.toString(before));
        assertTrue(afterPlan.contains("PrometheusTableScan"), afterPlan);
        assertTrue(afterPlan.contains("mode=idle") || afterPlan.contains("mode = idle") || afterPlan.contains("idle"), afterPlan);
        assertTrue(!afterPlan.contains("LogicalFilter"), afterPlan);

        try (Connection connection = DriverManager.getConnection(URL);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            assertEquals(5.0, ((Number) resultSet.getObject(1)).doubleValue(), 0.0001);
        }
    }

    @Test
    void unboundedScanHitsRowCap() throws Exception {
        try (Connection connection = DriverManager.getConnection(URL);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT * FROM node_cpu_seconds_total")) {
            while (resultSet.next()) {
                // drain
            }
            fail("row cap was not enforced");
        } catch (Throwable thrown) {
            assertTrue(messages(thrown).contains("превышен лимит строк"), messages(thrown));
        }
    }

    @Test
    void joinMatchesInstanceHostByStrippingPort() throws Exception {
        String sql = """
                SELECT p.pod_name, c.instance
                FROM node_cpu_seconds_total AS c
                JOIN kube_pod_info AS p
                  ON SUBSTRING(c.instance, 1, POSITION(':' IN c.instance) - 1) = p.host_ip
                WHERE c.mode = 'idle' AND p.pod_name = 'nginx'
                """;
        try (Connection connection = DriverManager.getConnection(URL);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int rows = 0;
            while (resultSet.next()) {
                rows++;
                assertEquals("nginx", resultSet.getString(1));
                assertEquals("10.0.0.5:9100", resultSet.getString(2));
            }
            assertEquals(3, rows);
        }
    }

    private static RelNode logicalPlan(String sql) throws RelConversionException, ValidationException, org.apache.calcite.sql.parser.SqlParseException {
        SchemaPlus root = Frameworks.createRootSchema(true);
        SchemaPlus prometheus = root.add("prometheus", new PrometheusSchema());
        Planner planner = Frameworks.getPlanner(Frameworks.newConfigBuilder()
                .defaultSchema(prometheus)
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

    private static String messages(Throwable thrown) {
        StringBuilder builder = new StringBuilder();
        Throwable current = thrown;
        while (current != null) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}

package org.lokrusta.sqlprometheus;

import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PrometheusJdbcIntegrationTest {
    private static final int PROMETHEUS_PORT = 9090;
    private static final String PROMETHEUS_CONFIG = """
            global:
              scrape_interval: 5s
              scrape_timeout: 5s
            scrape_configs:
              - job_name: node-exporter
                static_configs:
                  - targets: ["node-exporter:9100"]
                relabel_configs:
                  - target_label: instance
                    replacement: localhost
              - job_name: cadvisor
                static_configs:
                  - targets: ["cadvisor:8080"]
                relabel_configs:
                  - target_label: instance
                    replacement: localhost
            """;

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final GenericContainer<?> NODE_EXPORTER = new GenericContainer<>("prom/node-exporter:latest")
            .withNetwork(NETWORK)
            .withNetworkAliases("node-exporter")
            .withExposedPorts(9100)
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final GenericContainer<?> CADVISOR = new GenericContainer<>("gcr.io/cadvisor/cadvisor:v0.49.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("cadvisor")
            .withExposedPorts(8080)
            .withPrivilegedMode(true)
            .withCreateContainerCmdModifier(cmd -> {
                HostConfig host = cmd.getHostConfig();
                host.withBinds(
                        Bind.parse("/var/run:/var/run:ro"),
                        Bind.parse("/sys:/sys:ro"),
                        Bind.parse("/var/lib/docker:/var/lib/docker:ro"));
            })
            .withStartupTimeout(Duration.ofMinutes(5));

    @Container
    static final GenericContainer<?> PROMETHEUS = new GenericContainer<>("prom/prometheus:latest")
            .withNetwork(NETWORK)
            .withExposedPorts(PROMETHEUS_PORT)
            .withCopyToContainer(Transferable.of(PROMETHEUS_CONFIG), "/etc/prometheus/prometheus.yml")
            .dependsOn(NODE_EXPORTER, CADVISOR)
            .waitingFor(Wait.forHttp("/-/ready").forPort(PROMETHEUS_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    @BeforeAll
    static void waitForFirstScrape() throws Exception {
        Class.forName("org.lokrusta.sqlprometheus.driver.PrometheusDriver");
        Thread.sleep(25000);
    }

    @Test
    void joinContainerCpuShareOnLivePrometheus() throws Exception {
        RelNode logical = PrometheusDriverTest.logicalPlan(PrometheusDriverTest.CPU_SHARE_SQL);
        String plan = PrometheusDriverTest.pushedPlan(logical);
        System.out.println(RelOptUtil.toString(logical));
        System.out.println(plan);
        assertEquals(2, count(plan, "PrometheusTableScan"), plan);
        assertEquals(2, count(plan, "start=["), plan);
        assertTrue(plan.contains("container_cpu_usage_seconds_total"), plan);
        assertTrue(plan.contains("node_cpu_seconds_total"), plan);
        assertFalse(plan.contains("CURRENT_TIMESTAMP"), plan);

        String url = "jdbc:prometheus://localhost:" + PROMETHEUS.getMappedPort(PROMETHEUS_PORT);
        Instant end = Instant.now().minusSeconds(12).truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minus(5, ChronoUnit.MINUTES);
        String startText = timestamp(start);
        String endText = timestamp(end);
        try (Connection connection = DriverManager.getConnection(url);
             Statement statement = connection.createStatement()) {
            Map<String, Double> live = percentages(statement, PrometheusDriverTest.CPU_SHARE_SQL);
            System.out.println(live);
            assertFalse(live.isEmpty(), live.toString());
            assertTrue(live.keySet().stream().anyMatch(image -> image.contains("cadvisor")), live.toString());
            assertTrue(live.values().stream().allMatch(value -> value > 0 && Double.isFinite(value)), live.toString());

            String bounded = """
                    SELECT c.image, (SUM(c.value) / SUM(n.value)) * 100 AS cpu_percentage
                    FROM container_cpu_usage_seconds_total c
                    JOIN node_cpu_seconds_total n ON c.instance = n.instance
                    WHERE c.timestamp > TIMESTAMP '%s'
                      AND c.timestamp < TIMESTAMP '%s'
                      AND c.image IS NOT NULL
                    GROUP BY c.image
                    """.formatted(startText, endText);
            Map<String, Double> actual = percentages(statement, bounded);
            Map<String, Double> expected = expectedPercentages(statement, startText, endText);
            assertFalse(actual.isEmpty(), actual.toString());
            assertEquals(expected.keySet(), actual.keySet(), actual + " vs " + expected);
            for (String image : expected.keySet()) {
                assertEquals(expected.get(image), actual.get(image), 0.0001, "'" + image + "'");
            }
        }
    }

    private static String timestamp(Instant instant) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneOffset.UTC)
                .format(instant);
    }

    private static Map<String, Double> expectedPercentages(Statement statement, String start, String end) throws Exception {
        Map<String, Double> sumContainer = new HashMap<>();
        Map<String, Double> sumNode = new HashMap<>();
        try (ResultSet resultSet = statement.executeQuery("""
                SELECT c.image, c.value, n.value
                FROM container_cpu_usage_seconds_total c
                JOIN node_cpu_seconds_total n ON c.instance = n.instance
                WHERE c.timestamp > TIMESTAMP '%s'
                  AND c.timestamp < TIMESTAMP '%s'
                  AND c.image IS NOT NULL
                """.formatted(start, end))) {
            while (resultSet.next()) {
                String image = resultSet.getString(1);
                sumContainer.merge(image, resultSet.getDouble(2), Double::sum);
                sumNode.merge(image, resultSet.getDouble(3), Double::sum);
            }
        }
        Map<String, Double> percentages = new LinkedHashMap<>();
        for (String image : sumContainer.keySet()) {
            percentages.put(image, sumContainer.get(image) / sumNode.get(image) * 100.0);
        }
        return percentages;
    }

    private static Map<String, Double> percentages(Statement statement, String sql) throws Exception {
        Map<String, Double> byImage = new LinkedHashMap<>();
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                byImage.put(resultSet.getString(1), resultSet.getDouble(2));
            }
        }
        return byImage;
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

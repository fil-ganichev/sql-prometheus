package org.lokrusta.sqlprometheus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.HostConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.lokrusta.sqlprometheus.dto.LabelValuesResponse;
import org.lokrusta.sqlprometheus.dto.MatrixSeries;
import org.lokrusta.sqlprometheus.dto.QueryRangeResponse;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Testcontainers(disabledWithoutDocker = true)
class PrometheusDataDiscoveryTest {
    private static final int PROMETHEUS_PORT = 9090;
    private static final Path SNAPSHOTS = Path.of("src", "test", "resources", "snapshots");
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
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
            .withNetworkAliases("prometheus")
            .withExposedPorts(PROMETHEUS_PORT)
            .withCopyToContainer(Transferable.of(PROMETHEUS_CONFIG), "/etc/prometheus/prometheus.yml")
            .dependsOn(NODE_EXPORTER, CADVISOR)
            .waitingFor(Wait.forHttp("/-/ready").forPort(PROMETHEUS_PORT).forStatusCode(200))
            .withStartupTimeout(Duration.ofMinutes(5));

    @BeforeAll
    static void waitForFirstScrape() throws InterruptedException {
        Thread.sleep(15000);
    }

    @Test
    void test_DiscoverCatalog() throws Exception {
        LabelValuesResponse catalog = get(
                "/api/v1/label/__name__/values",
                SNAPSHOTS.resolve("catalog_live.json"),
                LabelValuesResponse.class);
        assertEquals("success", catalog.status());
        assertNotNull(catalog.data());
        assertFalse(catalog.data().isEmpty());
    }

    @Test
    void test_DiscoverNodeCpuRaw() throws Exception {
        QueryRangeResponse series = queryRange("node_cpu_seconds_total", SNAPSHOTS.resolve("node_cpu_raw.json"));
        assertSamples(series, "node_cpu_seconds_total");
    }

    @Test
    void test_DiscoverContainerCpuRaw() throws Exception {
        QueryRangeResponse series = queryRange(
                "container_cpu_usage_seconds_total",
                SNAPSHOTS.resolve("container_cpu_raw.json"));
        assertSamples(series, "container_cpu_usage_seconds_total");
    }

    private static QueryRangeResponse queryRange(String metric, Path file) throws Exception {
        Instant end = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant start = end.minus(5, ChronoUnit.MINUTES);
        String path = "/api/v1/query_range?query=" + encode(metric)
                + "&start=" + encode(start.toString())
                + "&end=" + encode(end.toString())
                + "&step=15s";
        QueryRangeResponse response = get(path, file, QueryRangeResponse.class);
        assertEquals("success", response.status(), response.error());
        assertNotNull(response.data());
        return response;
    }

    private static void assertSamples(QueryRangeResponse series, String metric) {
        assertEquals("matrix", series.data().resultType());
        assertFalse(series.data().result().isEmpty(), metric + " не вернул ни одного ряда");
        for (MatrixSeries row : series.data().result()) {
            assertEquals("localhost", row.metric().get("instance"));
            assertFalse(row.values().isEmpty());
        }
    }

    private static <T> T get(String pathAndQuery, Path file, Class<T> type) throws Exception {
        URI uri = URI.create("http://" + PROMETHEUS.getHost() + ":"
                + PROMETHEUS.getMappedPort(PROMETHEUS_PORT) + pathAndQuery);
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), response.body());
        Files.createDirectories(SNAPSHOTS);
        JSON.writeValue(file.toFile(), JSON.readTree(response.body()));
        return JSON.readValue(Objects.requireNonNull(response.body()), type);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}

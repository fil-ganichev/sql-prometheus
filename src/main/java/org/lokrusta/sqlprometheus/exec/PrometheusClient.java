package org.lokrusta.sqlprometheus.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PrometheusClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<Integer, HttpClient> CLIENTS = new ConcurrentHashMap<>();

    private PrometheusClient() {
    }

    public static String queryRange(String baseUrl,
                                    String metric,
                                    Map<String, String> labelFilters,
                                    long startMillis,
                                    long endMillis,
                                    String step,
                                    int connectTimeoutSeconds,
                                    int queryTimeoutSeconds,
                                    String promFunction,
                                    String promWindow,
                                    String promArgs,
                                    String rateWindow,
                                    String quantilePhi,
                                    String quantileBy,
                                    String scalars,
                                    String innerScalars,
                                    String agg,
                                    String aggBy) {
        String query = selector(metric, labelFilters, promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, scalars, innerScalars, agg, aggBy);
        String url = stripSlash(baseUrl) + "/api/v1/query_range?query=" + encode(query)
                + "&start=" + seconds(startMillis)
                + "&end=" + seconds(endMillis)
                + "&step=" + encode(step);
        try {
            HttpResponse<String> response = client(connectTimeoutSeconds).send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(queryTimeoutSeconds))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Prometheus HTTP " + response.statusCode() + ": " + response.body());
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prometheus query interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Prometheus query failed: " + url, e);
        }
    }

    public static SeriesMetadata series(String baseUrl,
                                       String metric,
                                       int connectTimeoutSeconds,
                                       int queryTimeoutSeconds) {
        String url = stripSlash(baseUrl) + "/api/v1/series?match%5B%5D=" + encode(metric);
        try {
            HttpResponse<String> response = client(connectTimeoutSeconds).send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(queryTimeoutSeconds))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Prometheus HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = JSON.readTree(response.body());
            if (!"success".equals(root.path("status").asText())) {
                throw new IllegalStateException("Prometheus series failed: " + response.body());
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                return SeriesMetadata.absent();
            }
            Set<String> labels = new LinkedHashSet<>();
            for (JsonNode series : data) {
                series.fieldNames().forEachRemaining(name -> {
                    if (!"__name__".equals(name)) {
                        labels.add(name);
                    }
                });
            }
            List<String> sorted = new ArrayList<>(labels);
            Collections.sort(sorted);
            return new SeriesMetadata(true, sorted);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Prometheus series interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Prometheus series failed: " + url, e);
        }
    }

    private static String selector(String metric,
                                   Map<String, String> labelFilters,
                                   String promFunction,
                                   String promWindow,
                                   String promArgs,
                                   String rateWindow,
                                   String quantilePhi,
                                   String quantileBy,
                                   String scalars,
                                   String innerScalars,
                                   String agg,
                                   String aggBy) {
        String series = metric;
        if (labelFilters != null && !labelFilters.isEmpty()) {
            String labels = labelFilters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=\"" + escape(entry.getValue()) + "\"")
                    .collect(Collectors.joining(","));
            series = metric + "{" + labels + "}";
        }
        String core;
        if (quantilePhi != null && !quantilePhi.isEmpty()) {
            if (rateWindow == null || rateWindow.isEmpty()) {
                throw new IllegalStateException("histogram_quantile requires a range window");
            }
            String by = quantileBy == null || quantileBy.isEmpty() ? "le" : quantileBy;
            core = "histogram_quantile(" + quantilePhi + ", sum by (" + by + ") (rate(" + series + "[" + rateWindow + "])))";
        } else if (promFunction != null && !promFunction.isEmpty()) {
            core = promFunction + "(" + series + "[" + promWindow + "]";
            if (promArgs != null && !promArgs.isEmpty()) {
                core += ", " + promArgs;
            }
            core += ")";
        } else {
            core = series;
        }
        core = applyScalars(core, innerScalars);
        if (agg != null && !agg.isEmpty()) {
            if (aggBy == null || aggBy.isEmpty()) {
                core = agg + "(" + core + ")";
            } else {
                core = agg + " by (" + aggBy + ") (" + core + ")";
            }
        }
        return applyScalars(core, scalars);
    }

    private static String applyScalars(String core, String scalars) {
        if (scalars == null || scalars.isEmpty()) {
            return core;
        }
        for (String scalar : scalars.split(",")) {
            if (!scalar.isEmpty()) {
                core = scalar + "(" + core + ")";
            }
        }
        return core;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String seconds(long millis) {
        return String.format(Locale.US, "%.3f", millis / 1000.0);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static HttpClient client(int connectTimeoutSeconds) {
        return CLIENTS.computeIfAbsent(connectTimeoutSeconds, seconds -> HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(seconds))
                .build());
    }

    private static String stripSlash(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }
}

package org.lokrusta.sqlprometheus.exec;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class PrometheusClient {
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
                                    String promWindow) {
        String query = selector(metric, labelFilters, promFunction, promWindow);
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

    private static String selector(String metric,
                                   Map<String, String> labelFilters,
                                   String promFunction,
                                   String promWindow) {
        String series = metric;
        if (labelFilters != null && !labelFilters.isEmpty()) {
            String labels = labelFilters.entrySet().stream()
                    .map(entry -> entry.getKey() + "=\"" + escape(entry.getValue()) + "\"")
                    .collect(Collectors.joining(","));
            series = metric + "{" + labels + "}";
        }
        if (promFunction == null || promFunction.isEmpty()) {
            return series;
        }
        return promFunction + "(" + series + "[" + promWindow + "])";
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

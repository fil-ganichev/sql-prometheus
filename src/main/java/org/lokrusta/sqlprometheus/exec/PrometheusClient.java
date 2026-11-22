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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class PrometheusClient {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ConcurrentHashMap<Integer, HttpClient> CLIENTS = new ConcurrentHashMap<>();
    private static final Pattern WHEN_ARGS = Pattern.compile(
            "^(>=|<=|==|>|<) (-?\\d+(?:\\.\\d+)?)(?:;(.*))?$");

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
                                    String aggBy,
                                    String callShape) {
        String query = selector(metric, labelFilters, promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, scalars, innerScalars, agg, aggBy, callShape);
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
                String function = promFunction == null || promFunction.isEmpty() ? "" : " (" + promFunction + ")";
                throw new IllegalStateException(
                        "Prometheus HTTP " + response.statusCode() + ": " + response.body() + function);
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
                                   String aggBy,
                                   String callShape) {
        PrometheusCallShape shape = PrometheusCallShape.parse(callShape);
        String offset = shape.offset() == null || shape.offset().isEmpty() ? "" : " offset " + shape.offset();
        String series = metric;
        if (labelFilters != null && !labelFilters.isEmpty()) {
            String labels = labelFilters.entrySet().stream()
                    .map(entry -> labelMatcher(entry.getKey(), entry.getValue()))
                    .collect(Collectors.joining(","));
            series = metric + "{" + labels + "}";
        }
        String core;
        if (quantilePhi != null && !quantilePhi.isEmpty()) {
            if (rateWindow == null || rateWindow.isEmpty()) {
                throw new IllegalStateException("histogram_quantile requires a range window");
            }
            String by = quantileBy == null || quantileBy.isEmpty() ? "le" : quantileBy;
            core = "histogram_quantile(" + quantilePhi + ", sum by (" + by + ") (rate(" + series + "[" + rateWindow + "]" + offset + ")))";
        } else if (promFunction != null && !promFunction.isEmpty()) {
            Matcher when = promArgs == null ? null : WHEN_ARGS.matcher(promArgs);
            if (when != null && when.matches()) {
                String range = "(" + series + " " + when.group(1) + " " + when.group(2) + ")[" + promWindow + "]" + offset;
                if ("quantile_over_time".equals(promFunction)) {
                    if (when.group(3) == null || when.group(3).isEmpty()) {
                        throw new IllegalStateException("quantile_over_time requires a phi between 0 and 1");
                    }
                    core = promFunction + "(" + when.group(3) + ", " + range + ")";
                } else {
                    core = promFunction + "(" + range;
                    if (when.group(3) != null) {
                        core += ", " + when.group(3);
                    }
                    core += ")";
                }
            } else if ("quantile_over_time".equals(promFunction)) {
                if (promArgs == null || promArgs.isEmpty()) {
                    throw new IllegalStateException("quantile_over_time requires a phi between 0 and 1");
                }
                core = promFunction + "(" + promArgs + ", " + series + "[" + promWindow + "]" + offset + ")";
            } else {
                core = promFunction + "(" + series + "[" + promWindow + "]" + offset;
                if (promArgs != null && !promArgs.isEmpty()) {
                    core += ", " + promArgs;
                }
                core += ")";
            }
        } else {
            core = series + offset;
        }
        if (shape.outerFunction() != null && !shape.outerFunction().isEmpty()) {
            core = shape.outerFunction() + "(" + core + "[" + shape.outerWindow() + "])";
        }
        if (shape.pipe() != null && !shape.pipe().isEmpty()) {
            String[] tokens = shape.pipe().split(",");
            for (int i = tokens.length - 1; i >= 0; i--) {
                core = applyToken(core, tokens[i], shape, agg, aggBy);
            }
            return core;
        }
        core = applyScalars(core, innerScalars);
        core = applyAggregate(core, agg, aggBy);
        return applyScalars(core, scalars);
    }

    private static String applyToken(String core, String token, PrometheusCallShape shape, String agg, String aggBy) {
        if ("k".equals(token)) {
            return shape.rank() + "(" + shape.rankCount() + ", " + core + ")";
        }
        if ("l".equals(token)) {
            return "label_replace(" + core
                    + ", \"" + escape(shape.labelDst()) + "\""
                    + ", \"" + escape(shape.labelRepl()) + "\""
                    + ", \"" + escape(shape.labelSrc()) + "\""
                    + ", \"" + escape(shape.labelRegex()) + "\")";
        }
        if ("a".equals(token)) {
            return applyAggregate(core, agg, aggBy);
        }
        if (token.startsWith("s:")) {
            return token.substring(2) + "(" + core + ")";
        }
        throw new IllegalStateException("Prometheus call shape is invalid");
    }

    static String labelMatcher(String name, String stored) {
        if (stored != null && stored.startsWith("!=")) {
            return name + "!=\"" + escape(stored.substring(2)) + "\"";
        }
        return name + "=\"" + escape(stored) + "\"";
    }

    private static String applyAggregate(String core, String agg, String aggBy) {
        if (agg == null || agg.isEmpty()) {
            return core;
        }
        if (aggBy == null || aggBy.isEmpty()) {
            return agg + "(" + core + ")";
        }
        return agg + " by (" + aggBy + ") (" + core + ")";
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

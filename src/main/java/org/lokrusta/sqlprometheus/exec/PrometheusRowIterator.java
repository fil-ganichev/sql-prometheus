package org.lokrusta.sqlprometheus.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class PrometheusRowIterator implements Iterator<Object[]> {
    public static final int DEFAULT_MAX_ROWS = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Object[]> rows;
    private final int maxRows;
    private int index;
    private int returned;

    private PrometheusRowIterator(List<Object[]> rows, int maxRows) {
        this.rows = rows;
        this.maxRows = maxRows;
    }

    public static PrometheusRowIterator query(String baseUrl,
                                             String metric,
                                             List<String> labelColumns,
                                             Map<String, String> labelFilters,
                                             long startMillis,
                                             long endMillis,
                                             int maxRows,
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
        String json = PrometheusClient.queryRange(
                baseUrl, metric, labelFilters, startMillis, endMillis, step, connectTimeoutSeconds, queryTimeoutSeconds,
                promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, scalars, innerScalars, agg, aggBy);
        boolean latestSample = quantilePhi != null && !quantilePhi.isEmpty();
        return fromJson(json, labelColumns, labelFilters, startMillis, endMillis, maxRows, latestSample);
    }

    public static PrometheusRowIterator fromJson(String json,
                                                 List<String> labelColumns,
                                                 long startMillis,
                                                 long endMillis) {
        return fromJson(json, labelColumns, startMillis, endMillis, DEFAULT_MAX_ROWS);
    }

    public static PrometheusRowIterator fromJson(String json,
                                                 List<String> labelColumns,
                                                 long startMillis,
                                                 long endMillis,
                                                 int maxRows) {
        return fromJson(json, labelColumns, Map.of(), startMillis, endMillis, maxRows, false);
    }

    private static PrometheusRowIterator fromJson(String json,
                                                  List<String> labelColumns,
                                                  Map<String, String> labelFilters,
                                                  long startMillis,
                                                  long endMillis,
                                                  int maxRows,
                                                  boolean latestSample) {
        List<Object[]> rows = parse(json, labelColumns, labelFilters, startMillis, endMillis);
        return new PrometheusRowIterator(latestSample ? latest(rows, labelColumns.size()) : rows, maxRows);
    }

    private static List<Object[]> latest(List<Object[]> rows, int labelCount) {
        Map<String, Object[]> newest = new LinkedHashMap<>();
        for (Object[] row : rows) {
            StringBuilder key = new StringBuilder();
            for (int i = 0; i < labelCount; i++) {
                key.append('\0').append(row[2 + i]);
            }
            Object[] previous = newest.get(key.toString());
            if (previous == null || ((Number) row[0]).longValue() >= ((Number) previous[0]).longValue()) {
                newest.put(key.toString(), row);
            }
        }
        return new ArrayList<>(newest.values());
    }

    @Override
    public boolean hasNext() {
        return index < rows.size();
    }

    @Override
    public Object[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        returned++;
        if (returned > maxRows) {
            throw new RuntimeException("Запрос заблокирован: превышен лимит строк для защиты памяти (" + maxRows + ")");
        }
        return rows.get(index++);
    }

    private static List<Object[]> parse(String json,
                                        List<String> labelColumns,
                                        Map<String, String> labelFilters,
                                        long startMillis,
                                        long endMillis) {
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!"success".equals(root.path("status").asText())) {
                throw new IllegalStateException("Prometheus error: " + root.path("error").asText(json));
            }
            List<Object[]> parsed = new ArrayList<>();
            for (JsonNode series : root.path("data").path("result")) {
                JsonNode labels = series.path("metric");
                if (!matches(labels, labelFilters)) {
                    continue;
                }
                for (JsonNode sample : series.path("values")) {
                    long timestamp = Math.round(sample.get(0).asDouble() * 1000.0);
                    if (timestamp < startMillis || timestamp > endMillis) {
                        continue;
                    }
                    Object[] row = new Object[2 + labelColumns.size()];
                    row[0] = timestamp;
                    row[1] = Double.parseDouble(sample.get(1).asText());
                    for (int i = 0; i < labelColumns.size(); i++) {
                        row[2 + i] = labelValue(labels, labelColumns.get(i));
                    }
                    parsed.add(row);
                }
            }
            return parsed;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read Prometheus response", e);
        }
    }

    private static Object labelValue(JsonNode labels, String name) {
        String text = labels.path(name).asText();
        if (!"le".equals(name)) {
            return text;
        }
        if (text == null || text.isEmpty()) {
            return null;
        }
        return Double.parseDouble(text);
    }

    private static boolean matches(JsonNode labels, Map<String, String> labelFilters) {
        for (Map.Entry<String, String> filter : labelFilters.entrySet()) {
            if (!filter.getValue().equals(labels.path(filter.getKey()).asText())) {
                return false;
            }
        }
        return true;
    }
}

package org.lokrusta.sqlprometheus.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Iterator;
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
                                             int queryTimeoutSeconds) {
        String json = PrometheusClient.queryRange(
                baseUrl, metric, labelFilters, startMillis, endMillis, step, connectTimeoutSeconds, queryTimeoutSeconds);
        return fromJson(json, labelColumns, labelFilters, startMillis, endMillis, maxRows);
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
        return fromJson(json, labelColumns, Map.of(), startMillis, endMillis, maxRows);
    }

    private static PrometheusRowIterator fromJson(String json,
                                                  List<String> labelColumns,
                                                  Map<String, String> labelFilters,
                                                  long startMillis,
                                                  long endMillis,
                                                  int maxRows) {
        return new PrometheusRowIterator(parse(json, labelColumns, labelFilters, startMillis, endMillis), maxRows);
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
                        row[2 + i] = labels.path(labelColumns.get(i)).asText();
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

    private static boolean matches(JsonNode labels, Map<String, String> labelFilters) {
        for (Map.Entry<String, String> filter : labelFilters.entrySet()) {
            if (!filter.getValue().equals(labels.path(filter.getKey()).asText())) {
                return false;
            }
        }
        return true;
    }
}

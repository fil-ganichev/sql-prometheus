package org.lokrusta.sqlprometheus.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

public class PrometheusRowIterator implements Iterator<Object[]> {
    public static final int MAX_ROWS = 1000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final List<Object[]> rows;
    private int index;
    private int returned;

    public PrometheusRowIterator(String metric,
                                 List<String> labelColumns,
                                 Map<String, String> labelFilters,
                                 long startMillis,
                                 long endMillis) {
        this.rows = readRows(metric, labelColumns, labelFilters, startMillis, endMillis);
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
        if (returned > MAX_ROWS) {
            throw new RuntimeException("Запрос заблокирован: превышен лимит строк для защиты памяти");
        }
        return rows.get(index++);
    }

    private static List<Object[]> readRows(String metric,
                                           List<String> labelColumns,
                                           Map<String, String> labelFilters,
                                           long startMillis,
                                           long endMillis) {
        try {
            JsonNode root = MAPPER.readTree(responseJson(metric, labelFilters, startMillis, endMillis));
            List<Object[]> parsed = new ArrayList<>();
            for (JsonNode series : root.path("data").path("result")) {
                JsonNode labels = series.path("metric");
                if (!matches(labels, labelFilters)) {
                    continue;
                }
                for (JsonNode sample : series.path("values")) {
                    long timestamp = sample.get(0).asLong();
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

    private static String responseJson(String metric,
                                       Map<String, String> labelFilters,
                                       long startMillis,
                                       long endMillis) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("status", "success");
        ObjectNode data = root.putObject("data");
        data.put("resultType", "matrix");
        ArrayNode result = data.putArray("result");
        if ("node_cpu_seconds_total".equals(metric) && labelFilters.isEmpty()) {
            addSeries(result, Map.of("instance", "10.0.0.5:9100", "mode", "idle"),
                    samples(startMillis, endMillis, MAX_ROWS + 1, 1.0));
        } else if ("node_cpu_seconds_total".equals(metric)) {
            long step = Math.max(1L, (endMillis - startMillis) / 4);
            addSeries(result, Map.of("instance", "10.0.0.5:9100", "mode", "idle"), List.of(
                    sample(startMillis, "2.0"),
                    sample(startMillis + step, "4.0"),
                    sample(startMillis + 2 * step, "6.0")));
            addSeries(result, Map.of("instance", "10.0.0.5:9100", "mode", "user"), List.of(
                    sample(startMillis, "100.0")));
            addSeries(result, Map.of("instance", "10.0.0.9:9100", "mode", "idle"), List.of(
                    sample(startMillis, "8.0")));
        } else {
            addSeries(result, Map.of("host_ip", "10.0.0.5", "pod_name", "nginx"), List.of(
                    sample(startMillis, "1.0")));
            addSeries(result, Map.of("host_ip", "10.0.0.8", "pod_name", "redis"), List.of(
                    sample(startMillis, "1.0")));
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void addSeries(ArrayNode result, Map<String, String> labels, List<double[]> samples) {
        ObjectNode series = result.addObject();
        ObjectNode metric = series.putObject("metric");
        labels.forEach(metric::put);
        ArrayNode values = series.putArray("values");
        for (double[] sample : samples) {
            ArrayNode pair = values.addArray();
            pair.add((long) sample[0]);
            pair.add(Double.toString(sample[1]));
        }
    }

    private static List<double[]> samples(long startMillis, long endMillis, int count, double value) {
        List<double[]> samples = new ArrayList<>(count);
        long span = Math.max(1L, endMillis - startMillis);
        for (int i = 0; i < count; i++) {
            long timestamp = startMillis + (span * i) / count;
            samples.add(sample(timestamp, Double.toString(value)));
        }
        return samples;
    }

    private static double[] sample(long timestamp, String value) {
        return new double[]{timestamp, Double.parseDouble(value)};
    }
}

package org.lokrusta.sqlprometheus.exec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Сдвиг, topk/bottomk, замена существующего лейбла и один уровень подзапроса.
 * В запрос уходит одной строкой, чтобы не раздувать сигнатуру {@code query}.
 */
public record PrometheusCallShape(
        String offset,
        String rank,
        String rankCount,
        String outerFunction,
        String outerWindow,
        String labelDst,
        String labelRepl,
        String labelSrc,
        String labelRegex,
        String pipe) {
    public static final PrometheusCallShape EMPTY = new PrometheusCallShape(
            null, null, null, null, null, null, null, null, null, null);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public boolean isEmpty() {
        return offset == null
                && rank == null
                && outerFunction == null
                && labelDst == null
                && (pipe == null || pipe.isEmpty());
    }

    public String spec() {
        if (isEmpty()) {
            return null;
        }
        ObjectNode node = MAPPER.createObjectNode();
        put(node, "offset", offset);
        put(node, "rank", rank);
        put(node, "k", rankCount);
        put(node, "over", outerFunction);
        put(node, "overWindow", outerWindow);
        put(node, "dst", labelDst);
        put(node, "repl", labelRepl);
        put(node, "src", labelSrc);
        put(node, "re", labelRegex);
        put(node, "pipe", pipe);
        return node.toString();
    }

    public static PrometheusCallShape parse(String spec) {
        if (spec == null || spec.isEmpty()) {
            return EMPTY;
        }
        try {
            JsonNode node = MAPPER.readTree(spec);
            return new PrometheusCallShape(
                    text(node, "offset"),
                    text(node, "rank"),
                    text(node, "k"),
                    text(node, "over"),
                    text(node, "overWindow"),
                    text(node, "dst"),
                    text(node, "repl"),
                    text(node, "src"),
                    text(node, "re"),
                    text(node, "pipe"));
        } catch (Exception e) {
            throw new IllegalStateException("Prometheus call shape is invalid", e);
        }
    }

    private static void put(ObjectNode node, String name, String value) {
        if (value != null) {
            node.put(name, value);
        }
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }
}

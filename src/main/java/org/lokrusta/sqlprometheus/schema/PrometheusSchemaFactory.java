package org.lokrusta.sqlprometheus.schema;

import org.apache.calcite.schema.Schema;
import org.apache.calcite.schema.SchemaFactory;
import org.apache.calcite.schema.SchemaPlus;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;

import java.util.Map;

public class PrometheusSchemaFactory implements SchemaFactory {
    @Override
    public Schema create(SchemaPlus parentSchema, String name, Map<String, Object> operand) {
        Object baseUrl = operand.get("baseUrl");
        if (baseUrl == null || baseUrl.toString().isBlank()) {
            throw new IllegalArgumentException("Prometheus schema operand baseUrl is required");
        }
        return new PrometheusSchema(baseUrl.toString(), options(operand));
    }

    private static PrometheusOptions options(Map<String, Object> operand) {
        PrometheusOptions defaults = PrometheusOptions.defaults();
        return new PrometheusOptions(
                positive("maxRows", operand.get("maxRows"), defaults.maxRows()),
                operand.get("step") == null ? defaults.step() : PrometheusOptions.step(operand.get("step").toString()),
                positive("connectTimeout", operand.get("connectTimeout"), defaults.connectTimeoutSeconds()),
                positive("queryTimeout", operand.get("queryTimeout"), defaults.queryTimeoutSeconds()),
                positive("schemaCacheMaxBytes", operand.get("schemaCacheMaxBytes"), defaults.schemaCacheMaxBytes()),
                positive("schemaCacheTtlSeconds", operand.get("schemaCacheTtlSeconds"), defaults.schemaCacheTtlSeconds()));
    }

    private static int positive(String name, Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        return PrometheusOptions.positiveInt(name, raw.toString());
    }
}

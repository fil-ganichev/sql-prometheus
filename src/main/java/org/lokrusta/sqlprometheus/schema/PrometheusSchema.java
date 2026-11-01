package org.lokrusta.sqlprometheus.schema;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;

import java.util.Map;

public class PrometheusSchema extends AbstractSchema {
    private final String baseUrl;
    private final PrometheusOptions options;

    public PrometheusSchema(String baseUrl) {
        this(baseUrl, PrometheusOptions.defaults());
    }

    public PrometheusSchema(String baseUrl, PrometheusOptions options) {
        this.baseUrl = baseUrl;
        this.options = options;
    }

    @Override
    protected Map<String, Table> getTableMap() {
        return Map.of(
                "node_cpu_seconds_total", PrometheusTable.cpu(baseUrl, options),
                "container_cpu_usage_seconds_total", PrometheusTable.containerCpu(baseUrl, options),
                "kube_pod_info", PrometheusTable.podInfo(baseUrl, options));
    }
}

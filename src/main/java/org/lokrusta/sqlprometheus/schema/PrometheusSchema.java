package org.lokrusta.sqlprometheus.schema;

import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;

import java.util.Map;

public class PrometheusSchema extends AbstractSchema {
    @Override
    protected Map<String, Table> getTableMap() {
        return Map.of(
                "node_cpu_seconds_total", PrometheusTable.cpu(),
                "kube_pod_info", PrometheusTable.podInfo());
    }
}

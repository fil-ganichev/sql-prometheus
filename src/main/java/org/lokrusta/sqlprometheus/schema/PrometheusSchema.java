package org.lokrusta.sqlprometheus.schema;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Multimap;
import org.apache.calcite.schema.Function;
import org.apache.calcite.schema.Table;
import org.apache.calcite.schema.impl.AbstractSchema;
import org.apache.calcite.schema.impl.ScalarFunctionImpl;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlFunctions;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import java.util.Map;

public class PrometheusSchema extends AbstractSchema {
    private final String baseUrl;
    private final PrometheusOptions options;
    private final boolean sqlFunctions;

    public PrometheusSchema(String baseUrl) {
        this(baseUrl, PrometheusOptions.defaults());
    }

    public PrometheusSchema(String baseUrl, PrometheusOptions options) {
        this(baseUrl, options, true);
    }

    private PrometheusSchema(String baseUrl, PrometheusOptions options, boolean sqlFunctions) {
        this.baseUrl = baseUrl;
        this.options = options;
        this.sqlFunctions = sqlFunctions;
    }

    public static PrometheusSchema withoutFunctions(String baseUrl) {
        return new PrometheusSchema(baseUrl, PrometheusOptions.defaults(), false);
    }

    @Override
    protected Multimap<String, Function> getFunctionMultimap() {
        if (!sqlFunctions) {
            return ImmutableMultimap.of();
        }
        ImmutableMultimap.Builder<String, Function> builder = ImmutableMultimap.builder();
        for (String name : PrometheusSqlOperatorTable.functionNames()) {
            builder.put(name, ScalarFunctionImpl.create(PrometheusSqlFunctions.class, name));
        }
        return builder.build();
    }

    @Override
    protected Map<String, Table> getTableMap() {
        return Map.of(
                "node_cpu_seconds_total", PrometheusTable.cpu(baseUrl, options),
                "container_cpu_usage_seconds_total", PrometheusTable.containerCpu(baseUrl, options),
                "kube_pod_info", PrometheusTable.podInfo(baseUrl, options),
                "http_request_duration_seconds_bucket", PrometheusTable.requestDuration(baseUrl, options));
    }
}

package org.lokrusta.sqlprometheus.schema;

import org.apache.calcite.DataContext;
import org.apache.calcite.linq4j.AbstractEnumerable;
import org.apache.calcite.linq4j.Enumerable;
import org.apache.calcite.linq4j.Enumerator;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.schema.ScannableTable;
import org.apache.calcite.schema.Schema.TableType;
import org.apache.calcite.schema.Statistic;
import org.apache.calcite.schema.Statistics;
import org.apache.calcite.schema.TranslatableTable;
import org.apache.calcite.schema.impl.AbstractTable;
import org.apache.calcite.sql.type.SqlTypeName;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;
import org.lokrusta.sqlprometheus.exec.PrometheusRowIterator;
import org.lokrusta.sqlprometheus.rel.PrometheusTableScan;

import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.rel.RelNode;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrometheusTable extends AbstractTable implements ScannableTable, TranslatableTable {
    public static final long DEFAULT_RANGE_MILLIS = 15L * 60L * 1000L;

    private final String baseUrl;
    private final String metric;
    private final List<String> labelColumns;
    private final PrometheusOptions options;

    private PrometheusTable(String baseUrl, String metric, List<String> labelColumns, PrometheusOptions options) {
        this.baseUrl = baseUrl;
        this.metric = metric;
        this.labelColumns = List.copyOf(labelColumns);
        this.options = options;
    }

    public static PrometheusTable metric(String baseUrl, String name, List<String> labelColumns, PrometheusOptions options) {
        return new PrometheusTable(baseUrl, name, labelColumns, options);
    }

    public static PrometheusTable cpu(String baseUrl, PrometheusOptions options) {
        return new PrometheusTable(baseUrl, "node_cpu_seconds_total", List.of("instance", "mode"), options);
    }

    public static PrometheusTable podInfo(String baseUrl, PrometheusOptions options) {
        return new PrometheusTable(baseUrl, "kube_pod_info", List.of("node", "pod"), options);
    }

    public static PrometheusTable containerCpu(String baseUrl, PrometheusOptions options) {
        return new PrometheusTable(baseUrl, "container_cpu_usage_seconds_total", List.of("instance", "image"), options);
    }

    public static PrometheusTable requestDuration(String baseUrl, PrometheusOptions options) {
        return new PrometheusTable(
                baseUrl,
                "http_request_duration_seconds_bucket",
                List.of("service_name", "le", "instance"),
                options);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String metric() {
        return metric;
    }

    public List<String> labelColumns() {
        return labelColumns;
    }

    public PrometheusOptions options() {
        return options;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        builder.add("timestamp", SqlTypeName.TIMESTAMP);
        builder.add("value", SqlTypeName.DOUBLE);
        for (String label : labelColumns) {
            SqlTypeName type = "le".equals(label) ? SqlTypeName.DOUBLE : SqlTypeName.VARCHAR;
            builder.add(label, typeFactory.createSqlType(type));
        }
        return builder.build();
    }

    @Override
    public Statistic getStatistic() {
        return Statistics.UNKNOWN;
    }

    @Override
    public TableType getJdbcTableType() {
        return TableType.TABLE;
    }

    @Override
    public Enumerable<Object[]> scan(DataContext root) {
        return query(baseUrl, metric, String.join(",", labelColumns), "", null, null,
                options.maxRows(), options.step(), options.connectTimeoutSeconds(), options.queryTimeoutSeconds(),
                null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        return PrometheusTableScan.create(context.getCluster(), relOptTable, this);
    }

    public static Enumerable<Object[]> query(String baseUrl,
                                             String metric,
                                             String labelColumns,
                                             String filters,
                                             Long startMillis,
                                             Long endMillis,
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
        long end = endMillis != null ? endMillis : System.currentTimeMillis();
        long start = startMillis != null ? startMillis : end - DEFAULT_RANGE_MILLIS;
        List<String> columns = labelColumns == null || labelColumns.isEmpty()
                ? List.of()
                : Arrays.asList(labelColumns.split(","));
        Map<String, String> labelFilters = decodeFilters(filters);
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                PrometheusRowIterator rows = PrometheusRowIterator.query(
                        baseUrl, metric, columns, labelFilters, start, end, maxRows,
                        step, connectTimeoutSeconds, queryTimeoutSeconds,
                        promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, scalars, innerScalars, agg, aggBy);
                return new Enumerator<>() {
                    private Object[] current;

                    @Override
                    public Object[] current() {
                        return current;
                    }

                    @Override
                    public boolean moveNext() {
                        if (!rows.hasNext()) {
                            return false;
                        }
                        current = rows.next();
                        return true;
                    }

                    @Override
                    public void reset() {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private static Map<String, String> decodeFilters(String filters) {
        Map<String, String> decoded = new LinkedHashMap<>();
        if (filters == null || filters.isEmpty()) {
            return decoded;
        }
        for (String entry : filters.split("\n")) {
            int separator = entry.indexOf('=');
            if (separator > 0) {
                decoded.put(entry.substring(0, separator), entry.substring(separator + 1));
            }
        }
        return decoded;
    }
}

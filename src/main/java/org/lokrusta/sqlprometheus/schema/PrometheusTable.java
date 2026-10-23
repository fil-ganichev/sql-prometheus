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

    private final String metric;
    private final List<String> labelColumns;

    private PrometheusTable(String metric, List<String> labelColumns) {
        this.metric = metric;
        this.labelColumns = List.copyOf(labelColumns);
    }

    public static PrometheusTable cpu() {
        return new PrometheusTable("node_cpu_seconds_total", List.of("instance", "mode"));
    }

    public static PrometheusTable podInfo() {
        return new PrometheusTable("kube_pod_info", List.of("host_ip", "pod_name"));
    }

    public String metric() {
        return metric;
    }

    public List<String> labelColumns() {
        return labelColumns;
    }

    @Override
    public RelDataType getRowType(RelDataTypeFactory typeFactory) {
        RelDataTypeFactory.Builder builder = typeFactory.builder();
        builder.add("timestamp", SqlTypeName.BIGINT);
        builder.add("value", SqlTypeName.DOUBLE);
        for (String label : labelColumns) {
            builder.add(label, typeFactory.createSqlType(SqlTypeName.VARCHAR));
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
        return query(metric, String.join(",", labelColumns), "", null, null);
    }

    @Override
    public RelNode toRel(RelOptTable.ToRelContext context, RelOptTable relOptTable) {
        return PrometheusTableScan.create(context.getCluster(), relOptTable, this);
    }

    public static Enumerable<Object[]> query(String metric,
                                             String labelColumns,
                                             String filters,
                                             Long startMillis,
                                             Long endMillis) {
        long end = endMillis != null ? endMillis : System.currentTimeMillis();
        long start = startMillis != null ? startMillis : end - DEFAULT_RANGE_MILLIS;
        List<String> columns = labelColumns == null || labelColumns.isEmpty()
                ? List.of()
                : Arrays.asList(labelColumns.split(","));
        Map<String, String> labelFilters = decodeFilters(filters);
        return new AbstractEnumerable<>() {
            @Override
            public Enumerator<Object[]> enumerator() {
                PrometheusRowIterator rows = new PrometheusRowIterator(metric, columns, labelFilters, start, end);
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

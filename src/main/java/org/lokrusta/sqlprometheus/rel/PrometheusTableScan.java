package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelWriter;
import org.apache.calcite.rel.core.TableScan;
import org.lokrusta.sqlprometheus.schema.PrometheusTable;

import com.google.common.collect.ImmutableList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrometheusTableScan extends TableScan {
    private final PrometheusTable prometheusTable;
    private final Map<String, String> labelFilters;
    private final Long startMillis;
    private final Long endMillis;
    private final String promFunction;
    private final String promWindow;

    public PrometheusTableScan(RelOptCluster cluster,
                               RelTraitSet traitSet,
                               RelOptTable table,
                               PrometheusTable prometheusTable,
                               Map<String, String> labelFilters,
                               Long startMillis,
                               Long endMillis,
                               String promFunction,
                               String promWindow) {
        super(cluster, traitSet, ImmutableList.of(), table);
        this.prometheusTable = prometheusTable;
        this.labelFilters = Map.copyOf(labelFilters);
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.promFunction = promFunction;
        this.promWindow = promWindow;
    }

    public static PrometheusTableScan create(RelOptCluster cluster,
                                             RelOptTable table,
                                             PrometheusTable prometheusTable) {
        return new PrometheusTableScan(
                cluster,
                cluster.traitSetOf(Convention.NONE),
                table,
                prometheusTable,
                Map.of(),
                null,
                null,
                null,
                null);
    }

    public PrometheusTable getPrometheusTable() {
        return prometheusTable;
    }

    public Map<String, String> getLabelFilters() {
        return labelFilters;
    }

    public Long getStartMillis() {
        return startMillis;
    }

    public Long getEndMillis() {
        return endMillis;
    }

    public String getPromFunction() {
        return promFunction;
    }

    public String getPromWindow() {
        return promWindow;
    }

    public PrometheusTableScan withFilters(Map<String, String> labelFilters, Long startMillis, Long endMillis) {
        Map<String, String> merged = new LinkedHashMap<>(this.labelFilters);
        merged.putAll(labelFilters);
        return new PrometheusTableScan(
                getCluster(),
                getCluster().traitSetOf(Convention.NONE),
                getTable(),
                prometheusTable,
                merged,
                startMillis,
                endMillis,
                promFunction,
                promWindow);
    }

    public PrometheusTableScan withFunction(String promFunction, String promWindow) {
        return new PrometheusTableScan(
                getCluster(),
                getCluster().traitSetOf(Convention.NONE),
                getTable(),
                prometheusTable,
                labelFilters,
                startMillis,
                endMillis,
                promFunction,
                promWindow);
    }

    public String filterSpec() {
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : labelFilters.entrySet()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return builder.toString();
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new PrometheusTableScan(
                getCluster(),
                traitSet,
                getTable(),
                prometheusTable,
                labelFilters,
                startMillis,
                endMillis,
                promFunction,
                promWindow);
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        return super.explainTerms(pw)
                .itemIf("filters", labelFilters, !labelFilters.isEmpty())
                .itemIf("start", startMillis, startMillis != null)
                .itemIf("end", endMillis, endMillis != null)
                .itemIf("function", promFunction, promFunction != null)
                .itemIf("window", promWindow, promWindow != null);
    }
}

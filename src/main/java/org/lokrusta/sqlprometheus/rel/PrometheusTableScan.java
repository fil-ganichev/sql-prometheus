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

import java.util.ArrayList;
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
    private final String rateWindow;
    private final String quantilePhi;
    private final List<String> quantileBy;
    private final List<String> scalars;

    public PrometheusTableScan(RelOptCluster cluster,
                               RelTraitSet traitSet,
                               RelOptTable table,
                               PrometheusTable prometheusTable,
                               Map<String, String> labelFilters,
                               Long startMillis,
                               Long endMillis,
                               String promFunction,
                               String promWindow,
                               String rateWindow,
                               String quantilePhi,
                               List<String> quantileBy,
                               List<String> scalars) {
        super(cluster, traitSet, ImmutableList.of(), table);
        this.prometheusTable = prometheusTable;
        this.labelFilters = Map.copyOf(labelFilters);
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.promFunction = promFunction;
        this.promWindow = promWindow;
        this.rateWindow = rateWindow;
        this.quantilePhi = quantilePhi;
        this.quantileBy = quantileBy == null ? List.of() : List.copyOf(quantileBy);
        this.scalars = scalars == null ? List.of() : List.copyOf(scalars);
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
                null,
                null,
                null,
                List.of(),
                List.of());
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

    public String getRateWindow() {
        return rateWindow;
    }

    public String getQuantilePhi() {
        return quantilePhi;
    }

    public List<String> getQuantileBy() {
        return quantileBy;
    }

    public List<String> getScalars() {
        return scalars;
    }

    public String quantileBySpec() {
        return quantileBy.isEmpty() ? null : String.join(", ", quantileBy);
    }

    public String scalarSpec() {
        return scalars.isEmpty() ? null : String.join(",", scalars);
    }

    public PrometheusTableScan withFilters(Map<String, String> labelFilters, Long startMillis, Long endMillis) {
        Map<String, String> merged = new LinkedHashMap<>(this.labelFilters);
        merged.putAll(labelFilters);
        return rebuild(getTraitSet(), merged, startMillis, endMillis, promFunction, promWindow, rateWindow, quantilePhi, quantileBy, scalars);
    }

    public PrometheusTableScan withFunction(String promFunction, String promWindow) {
        return withCall(promFunction, promWindow, List.of());
    }

    public PrometheusTableScan withCall(String promFunction, String promWindow, List<String> scalars) {
        List<String> merged = new ArrayList<>(this.scalars);
        if (scalars != null) {
            merged.addAll(scalars);
        }
        String function = promFunction != null ? promFunction : this.promFunction;
        String window = promFunction != null ? promWindow : this.promWindow;
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, function, window, rateWindow, quantilePhi, quantileBy, merged);
    }

    public PrometheusTableScan withRateWindow(String rateWindow) {
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, promFunction, promWindow, rateWindow, quantilePhi, quantileBy, scalars);
    }

    public PrometheusTableScan withQuantile(String quantilePhi, List<String> quantileBy, String rateWindow) {
        String window = rateWindow != null ? rateWindow : this.rateWindow;
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, promFunction, promWindow, window, quantilePhi, quantileBy, scalars);
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
        return rebuild(traitSet, labelFilters, startMillis, endMillis, promFunction, promWindow, rateWindow, quantilePhi, quantileBy, scalars);
    }

    private PrometheusTableScan rebuild(RelTraitSet traitSet,
                                        Map<String, String> labelFilters,
                                        Long startMillis,
                                        Long endMillis,
                                        String promFunction,
                                        String promWindow,
                                        String rateWindow,
                                        String quantilePhi,
                                        List<String> quantileBy,
                                        List<String> scalars) {
        return new PrometheusTableScan(
                getCluster(),
                traitSet,
                getTable(),
                prometheusTable,
                labelFilters,
                startMillis,
                endMillis,
                promFunction,
                promWindow,
                rateWindow,
                quantilePhi,
                quantileBy,
                scalars);
    }

    @Override
    public RelWriter explainTerms(RelWriter pw) {
        return super.explainTerms(pw)
                .itemIf("filters", labelFilters, !labelFilters.isEmpty())
                .itemIf("start", startMillis, startMillis != null)
                .itemIf("end", endMillis, endMillis != null)
                .itemIf("function", promFunction, promFunction != null)
                .itemIf("window", promWindow, promWindow != null)
                .itemIf("range", rateWindow, rateWindow != null)
                .itemIf("quantile", quantilePhi, quantilePhi != null)
                .itemIf("sumBy", quantileBySpec(), quantilePhi != null)
                .itemIf("scalars", scalarSpec(), !scalars.isEmpty());
    }
}

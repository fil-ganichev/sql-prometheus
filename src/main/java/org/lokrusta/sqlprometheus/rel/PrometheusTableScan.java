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
    private final List<String> promArgs;
    private final String rateWindow;
    private final String quantilePhi;
    private final List<String> quantileBy;
    private final String agg;
    private final List<String> aggBy;
    private final List<String> innerScalars;
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
                               List<String> promArgs,
                               String rateWindow,
                               String quantilePhi,
                               List<String> quantileBy,
                               String agg,
                               List<String> aggBy,
                               List<String> innerScalars,
                               List<String> scalars) {
        super(cluster, traitSet, ImmutableList.of(), table);
        this.prometheusTable = prometheusTable;
        this.labelFilters = Map.copyOf(labelFilters);
        this.startMillis = startMillis;
        this.endMillis = endMillis;
        this.promFunction = promFunction;
        this.promWindow = promWindow;
        this.promArgs = promArgs == null ? List.of() : List.copyOf(promArgs);
        this.rateWindow = rateWindow;
        this.quantilePhi = quantilePhi;
        this.quantileBy = quantileBy == null ? List.of() : List.copyOf(quantileBy);
        this.agg = agg;
        this.aggBy = aggBy == null ? List.of() : List.copyOf(aggBy);
        this.innerScalars = innerScalars == null ? List.of() : List.copyOf(innerScalars);
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
                List.of(),
                null,
                null,
                List.of(),
                null,
                List.of(),
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

    public List<String> getPromArgs() {
        return promArgs;
    }

    public String promArgSpec() {
        return promArgs.isEmpty() ? null : String.join(", ", promArgs);
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

    public String getAgg() {
        return agg;
    }

    public List<String> getAggBy() {
        return aggBy;
    }

    public String aggBySpec() {
        return aggBy.isEmpty() ? null : String.join(", ", aggBy);
    }

    public List<String> getInnerScalars() {
        return innerScalars;
    }

    public String innerScalarSpec() {
        return innerScalars.isEmpty() ? null : String.join(",", innerScalars);
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
        return rebuild(getTraitSet(), merged, startMillis, endMillis, promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, agg, aggBy, innerScalars, scalars);
    }

    public PrometheusTableScan withFunction(String promFunction, String promWindow) {
        return withCall(promFunction, promWindow, List.of());
    }

    public PrometheusTableScan withCall(String promFunction, String promWindow, List<String> scalars) {
        return withCall(promFunction, promWindow, List.of(), null, List.of(), List.of(), scalars);
    }

    public PrometheusTableScan withCall(String promFunction,
                                        String promWindow,
                                        List<String> promArgs,
                                        String agg,
                                        List<String> aggBy,
                                        List<String> innerScalars,
                                        List<String> scalars) {
        List<String> merged = new ArrayList<>(this.scalars);
        if (scalars != null) {
            merged.addAll(scalars);
        }
        List<String> mergedInner = new ArrayList<>(this.innerScalars);
        if (innerScalars != null) {
            mergedInner.addAll(innerScalars);
        }
        String function = promFunction != null ? promFunction : this.promFunction;
        String window = promFunction != null ? promWindow : this.promWindow;
        List<String> args = promFunction != null ? (promArgs == null ? List.of() : promArgs) : this.promArgs;
        String aggregation = agg != null ? agg : this.agg;
        List<String> by = agg != null ? (aggBy == null ? List.of() : aggBy) : this.aggBy;
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, function, window, args, rateWindow, quantilePhi, quantileBy, aggregation, by, mergedInner, merged);
    }

    public PrometheusTableScan withRateWindow(String rateWindow) {
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, agg, aggBy, innerScalars, scalars);
    }

    public PrometheusTableScan withQuantile(String quantilePhi, List<String> quantileBy, String rateWindow) {
        String window = rateWindow != null ? rateWindow : this.rateWindow;
        return rebuild(getTraitSet(), labelFilters, startMillis, endMillis, promFunction, promWindow, promArgs, window, quantilePhi, quantileBy, agg, aggBy, innerScalars, scalars);
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
        return rebuild(traitSet, labelFilters, startMillis, endMillis, promFunction, promWindow, promArgs, rateWindow, quantilePhi, quantileBy, agg, aggBy, innerScalars, scalars);
    }

    private PrometheusTableScan rebuild(RelTraitSet traitSet,
                                        Map<String, String> labelFilters,
                                        Long startMillis,
                                        Long endMillis,
                                        String promFunction,
                                        String promWindow,
                                        List<String> promArgs,
                                        String rateWindow,
                                        String quantilePhi,
                                        List<String> quantileBy,
                                        String agg,
                                        List<String> aggBy,
                                        List<String> innerScalars,
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
                promArgs,
                rateWindow,
                quantilePhi,
                quantileBy,
                agg,
                aggBy,
                innerScalars,
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
                .itemIf("args", promArgSpec(), !promArgs.isEmpty())
                .itemIf("range", rateWindow, rateWindow != null)
                .itemIf("quantile", quantilePhi, quantilePhi != null)
                .itemIf("sumBy", quantileBySpec(), quantilePhi != null)
                .itemIf("inner", innerScalarSpec(), !innerScalars.isEmpty())
                .itemIf("agg", agg, agg != null)
                .itemIf("aggBy", aggBySpec(), !aggBy.isEmpty())
                .itemIf("scalars", scalarSpec(), !scalars.isEmpty());
    }
}

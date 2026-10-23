package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.adapter.enumerable.EnumerableConvention;
import org.apache.calcite.adapter.enumerable.EnumerableRel;
import org.apache.calcite.adapter.enumerable.EnumerableRelImplementor;
import org.apache.calcite.adapter.enumerable.JavaRowFormat;
import org.apache.calcite.adapter.enumerable.PhysType;
import org.apache.calcite.adapter.enumerable.PhysTypeImpl;
import org.apache.calcite.linq4j.tree.Blocks;
import org.apache.calcite.linq4j.tree.Expressions;
import org.apache.calcite.linq4j.tree.Types;
import org.apache.calcite.plan.RelOptCluster;
import org.apache.calcite.plan.RelOptTable;
import org.apache.calcite.plan.RelTraitSet;
import org.apache.calcite.rel.RelNode;
import org.lokrusta.sqlprometheus.schema.PrometheusTable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

public class PrometheusEnumerableScan extends PrometheusTableScan implements EnumerableRel {
    public PrometheusEnumerableScan(RelOptCluster cluster,
                                    RelTraitSet traitSet,
                                    RelOptTable table,
                                    PrometheusTable prometheusTable,
                                    Map<String, String> labelFilters,
                                    Long startMillis,
                                    Long endMillis) {
        super(cluster, traitSet, table, prometheusTable, labelFilters, startMillis, endMillis);
    }

    public static PrometheusEnumerableScan create(PrometheusTableScan logical) {
        return new PrometheusEnumerableScan(
                logical.getCluster(),
                logical.getTraitSet().replace(EnumerableConvention.INSTANCE),
                logical.getTable(),
                logical.getPrometheusTable(),
                logical.getLabelFilters(),
                logical.getStartMillis(),
                logical.getEndMillis());
    }

    @Override
    public RelNode copy(RelTraitSet traitSet, List<RelNode> inputs) {
        return new PrometheusEnumerableScan(
                getCluster(),
                traitSet,
                getTable(),
                getPrometheusTable(),
                getLabelFilters(),
                getStartMillis(),
                getEndMillis());
    }

    @Override
    public Result implement(EnumerableRelImplementor implementor, Prefer pref) {
        PhysType physType = PhysTypeImpl.of(
                implementor.getTypeFactory(),
                getRowType(),
                JavaRowFormat.ARRAY);
        Method query = Types.lookupMethod(
                PrometheusTable.class,
                "query",
                String.class,
                String.class,
                String.class,
                Long.class,
                Long.class);
        return implementor.result(physType, Blocks.toBlock(Expressions.call(
                query,
                Expressions.constant(getPrometheusTable().metric()),
                Expressions.constant(String.join(",", getPrometheusTable().labelColumns())),
                Expressions.constant(filterSpec()),
                constantLong(getStartMillis()),
                constantLong(getEndMillis()))));
    }

    private static org.apache.calcite.linq4j.tree.Expression constantLong(Long value) {
        return value == null
                ? Expressions.constant(null, Long.class)
                : Expressions.constant(value, Long.class);
    }
}

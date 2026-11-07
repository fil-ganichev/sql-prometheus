package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelCollation;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@code PERCENTILE_CONT(phi) WITHIN GROUP (ORDER BY le)} on a histogram bucket
 * metric into {@code histogram_quantile}. The ordered column must be the {@code le} label.
 * A join key of that metric is kept in {@code sum by}, so the quantile stays per join key.
 */
public class PrometheusQuantilePushDownRule extends RelRule<RelRule.Config> {
    private static final class Config extends FixedOperandConfig {
        private static final Config INSTANCE = new Config();

        private Config() {
            super("PrometheusQuantilePushDownRule",
                    b0 -> b0.operand(LogicalAggregate.class).anyInputs());
        }

        @Override
        public PrometheusQuantilePushDownRule toRule() {
            return new PrometheusQuantilePushDownRule(this);
        }
    }

    public static final PrometheusQuantilePushDownRule INSTANCE = Config.INSTANCE.toRule();

    private PrometheusQuantilePushDownRule(RelRule.Config config) {
        super(config);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalAggregate aggregate = call.rel(0);
        if (!Aggregate.isSimple(aggregate)) {
            return;
        }
        Quantile quantile = null;
        boolean otherAggregate = false;
        for (AggregateCall aggCall : aggregate.getAggCallList()) {
            if (!isPercentileCont(aggCall)) {
                otherAggregate = true;
                continue;
            }
            Quantile parsed = parse(aggregate, aggCall);
            if (parsed == null || quantile != null) {
                return;
            }
            quantile = parsed;
        }
        if (quantile == null) {
            return;
        }
        ValueCarrier carried = ensureValue(aggregate.getInput(), quantile.scan);
        if (carried == null) {
            return;
        }
        PrometheusTableScan pushed = quantile.scan.withQuantile(quantile.phi, quantile.sumBy, quantile.rateWindow);
        Map<PrometheusTableScan, PrometheusTableScan> replacements = new IdentityHashMap<>();
        replacements.put(quantile.scan, pushed);
        RelNode input = replaceScans(carried.node, replacements);
        quantile = quantile.withValueIndex(carried.index);
        if (otherAggregate) {
            List<AggregateCall> calls = new ArrayList<>();
            for (AggregateCall aggCall : aggregate.getAggCallList()) {
                calls.add(aggCall == quantile.call ? maxOf(aggCall, quantile.valueIndex) : aggCall);
            }
            call.transformTo(aggregate.copy(
                    aggregate.getTraitSet(),
                    input,
                    aggregate.getGroupSet(),
                    aggregate.getGroupSets(),
                    calls));
            return;
        }
        RelNode project = passThrough(aggregate, input, quantile.valueIndex);
        if (project != null) {
            call.transformTo(project);
        }
    }

    private static boolean isPercentileCont(AggregateCall call) {
        return "PERCENTILE_CONT".equalsIgnoreCase(call.getAggregation().getName());
    }

    private static Quantile parse(LogicalAggregate aggregate, AggregateCall call) {
        String phi = phiOf(aggregate, call);
        RelCollation collation = call.getCollation();
        if (phi == null || collation == null || collation.getFieldCollations().size() != 1) {
            return null;
        }
        int orderedField = collation.getFieldCollations().get(0).getFieldIndex();
        Target le = resolve(aggregate.getInput(), orderedField);
        if (le == null || le.column < 2 || le.scan.getQuantilePhi() != null || le.scan.getPromFunction() != null) {
            return null;
        }
        String orderedName = le.scan.getRowType().getFieldNames().get(le.column);
        if (!"le".equalsIgnoreCase(orderedName) || le.scan.getRateWindow() == null) {
            return null;
        }
        return new Quantile(call, le.scan, phi, sumBy(aggregate, le.scan), le.scan.getRateWindow(), -1);
    }

    private static String phiOf(LogicalAggregate aggregate, AggregateCall call) {
        if (call.rexList != null) {
            for (RexNode rex : call.rexList) {
                String phi = literalPhi(rex);
                if (phi != null) {
                    return phi;
                }
            }
        }
        for (int arg : call.getArgList()) {
            String phi = literalAt(aggregate.getInput(), arg);
            if (phi != null) {
                return phi;
            }
        }
        return null;
    }

    private static String literalAt(RelNode node, int index) {
        node = unwrap(node);
        if (node instanceof LogicalProject project
                && index >= 0
                && index < project.getProjects().size()) {
            return literalPhi(project.getProjects().get(index));
        }
        if (node instanceof LogicalFilter filter) {
            return literalAt(filter.getInput(), index);
        }
        return null;
    }

    private static ValueCarrier ensureValue(RelNode node, PrometheusTableScan scan) {
        node = unwrap(node);
        Integer present = outputIndex(node, scan, 1);
        if (present != null) {
            return new ValueCarrier(node, present);
        }
        if (node instanceof LogicalFilter filter) {
            ValueCarrier carried = ensureValue(filter.getInput(), scan);
            if (carried == null) {
                return null;
            }
            return new ValueCarrier(
                    filter.copy(filter.getTraitSet(), carried.node, filter.getCondition()),
                    carried.index);
        }
        if (!(node instanceof LogicalProject project)) {
            return null;
        }
        Integer inner = outputIndex(project.getInput(), scan, 1);
        RelNode input = project.getInput();
        if (inner == null) {
            ValueCarrier carried = ensureValue(project.getInput(), scan);
            if (carried == null) {
                return null;
            }
            inner = carried.index;
            input = carried.node;
            project = project.copy(project.getTraitSet(), input, project.getProjects(), project.getRowType());
        }
        RexBuilder rexBuilder = project.getCluster().getRexBuilder();
        List<RexNode> projects = new ArrayList<>(project.getProjects());
        projects.add(rexBuilder.makeInputRef(unwrap(input), inner));
        List<String> names = new ArrayList<>(project.getRowType().getFieldNames());
        names.add(names.contains("value") ? "prom_value" : "value");
        return new ValueCarrier(LogicalProject.create(input, List.of(), projects, names), projects.size() - 1);
    }

    private static String literalPhi(RexNode node) {
        if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
            return literalPhi(call.getOperands().get(0));
        }
        if (!(node instanceof RexLiteral literal) || !SqlTypeName.NUMERIC_TYPES.contains(literal.getTypeName())) {
            return null;
        }
        BigDecimal value = literal.getValueAs(BigDecimal.class);
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private static List<String> sumBy(LogicalAggregate aggregate, PrometheusTableScan scan) {
        List<String> names = new ArrayList<>();
        names.add("le");
        List<String> fields = scan.getRowType().getFieldNames();
        for (int group : aggregate.getGroupSet()) {
            addLabel(names, fields, resolve(aggregate.getInput(), group), scan);
        }
        collectJoinLabels(aggregate.getInput(), scan, names, fields);
        return List.copyOf(names);
    }

    private static void collectJoinLabels(RelNode node,
                                          PrometheusTableScan scan,
                                          List<String> names,
                                          List<String> fields) {
        node = unwrap(node);
        if (node instanceof LogicalFilter filter) {
            collectJoinLabels(filter.getInput(), scan, names, fields);
            return;
        }
        if (node instanceof LogicalProject project) {
            collectJoinLabels(project.getInput(), scan, names, fields);
            return;
        }
        if (node instanceof LogicalJoin join) {
            for (int bit : RelOptUtil.InputFinder.bits(join.getCondition())) {
                addLabel(names, fields, resolve(join, bit), scan);
            }
            collectJoinLabels(join.getLeft(), scan, names, fields);
            collectJoinLabels(join.getRight(), scan, names, fields);
        }
    }

    private static void addLabel(List<String> names, List<String> fields, Target target, PrometheusTableScan scan) {
        if (target == null || target.scan != scan || target.column < 2 || target.column >= fields.size()) {
            return;
        }
        String name = fields.get(target.column);
        if (!"le".equalsIgnoreCase(name) && !names.contains(name)) {
            names.add(name);
        }
    }

    private static AggregateCall maxOf(AggregateCall call, int valueIndex) {
        return AggregateCall.create(
                SqlStdOperatorTable.MAX,
                false,
                false,
                false,
                List.of(valueIndex),
                -1,
                RelCollations.EMPTY,
                call.getType(),
                call.name);
    }

    private static RelNode passThrough(LogicalAggregate aggregate, RelNode input, int valueIndex) {
        RexBuilder rexBuilder = aggregate.getCluster().getRexBuilder();
        List<RexNode> projects = new ArrayList<>();
        for (int group : aggregate.getGroupSet()) {
            projects.add(rexBuilder.makeInputRef(input, group));
        }
        projects.add(rexBuilder.makeInputRef(input, valueIndex));
        List<String> names = aggregate.getRowType().getFieldNames();
        if (projects.size() != names.size()) {
            return null;
        }
        return LogicalProject.create(input, List.of(), projects, names);
    }

    private static RelNode unwrap(RelNode node) {
        while (node instanceof HepRelVertex vertex) {
            node = vertex.getCurrentRel();
        }
        return node;
    }

    private static Target resolve(RelNode node, int index) {
        node = unwrap(node);
        if (node instanceof PrometheusTableScan scan) {
            return index >= 0 && index < scan.getRowType().getFieldCount() ? new Target(scan, index) : null;
        }
        if (node instanceof LogicalFilter filter) {
            return resolve(filter.getInput(), index);
        }
        if (node instanceof LogicalProject project
                && index >= 0
                && index < project.getProjects().size()
                && project.getProjects().get(index) instanceof RexInputRef ref) {
            return resolve(project.getInput(), ref.getIndex());
        }
        if (node instanceof LogicalJoin join) {
            RelNode left = unwrap(join.getLeft());
            int leftCount = left.getRowType().getFieldCount();
            if (index < leftCount) {
                return resolve(left, index);
            }
            return resolve(join.getRight(), index - leftCount);
        }
        return null;
    }

    private static Integer outputIndex(RelNode node, PrometheusTableScan target, int scanColumn) {
        node = unwrap(node);
        if (node == target) {
            return scanColumn;
        }
        if (node instanceof LogicalFilter filter) {
            return outputIndex(filter.getInput(), target, scanColumn);
        }
        if (node instanceof LogicalProject project) {
            Integer inner = outputIndex(project.getInput(), target, scanColumn);
            if (inner == null) {
                return null;
            }
            for (int i = 0; i < project.getProjects().size(); i++) {
                if (project.getProjects().get(i) instanceof RexInputRef ref && ref.getIndex() == inner) {
                    return i;
                }
            }
            return null;
        }
        if (node instanceof LogicalJoin join) {
            RelNode left = unwrap(join.getLeft());
            Integer onLeft = outputIndex(left, target, scanColumn);
            if (onLeft != null) {
                return onLeft;
            }
            Integer onRight = outputIndex(join.getRight(), target, scanColumn);
            if (onRight == null) {
                return null;
            }
            return left.getRowType().getFieldCount() + onRight;
        }
        return null;
    }

    private static RelNode replaceScans(RelNode node, Map<PrometheusTableScan, PrometheusTableScan> replacements) {
        node = unwrap(node);
        if (node instanceof PrometheusTableScan scan) {
            PrometheusTableScan pushed = replacements.get(scan);
            return pushed != null ? pushed : node;
        }
        if (node instanceof LogicalFilter filter) {
            RelNode input = replaceScans(filter.getInput(), replacements);
            return input == unwrap(filter.getInput())
                    ? node
                    : filter.copy(filter.getTraitSet(), input, filter.getCondition());
        }
        if (node instanceof LogicalProject project) {
            RelNode input = replaceScans(project.getInput(), replacements);
            return input == unwrap(project.getInput())
                    ? node
                    : project.copy(project.getTraitSet(), input, project.getProjects(), project.getRowType());
        }
        if (node instanceof LogicalJoin join) {
            RelNode left = replaceScans(join.getLeft(), replacements);
            RelNode right = replaceScans(join.getRight(), replacements);
            if (left == unwrap(join.getLeft()) && right == unwrap(join.getRight())) {
                return node;
            }
            return join.copy(join.getTraitSet(), join.getCondition(), left, right, join.getJoinType(), join.isSemiJoinDone());
        }
        return node;
    }

    private static final class Target {
        private final PrometheusTableScan scan;
        private final int column;

        private Target(PrometheusTableScan scan, int column) {
            this.scan = scan;
            this.column = column;
        }
    }

    private static final class Quantile {
        private final AggregateCall call;
        private final PrometheusTableScan scan;
        private final String phi;
        private final List<String> sumBy;
        private final String rateWindow;
        private final int valueIndex;

        private Quantile(AggregateCall call,
                         PrometheusTableScan scan,
                         String phi,
                         List<String> sumBy,
                         String rateWindow,
                         int valueIndex) {
            this.call = call;
            this.scan = scan;
            this.phi = phi;
            this.sumBy = sumBy;
            this.rateWindow = rateWindow;
            this.valueIndex = valueIndex;
        }

        private Quantile withValueIndex(int valueIndex) {
            return new Quantile(call, scan, phi, sumBy, rateWindow, valueIndex);
        }
    }

    private static final class ValueCarrier {
        private final RelNode node;
        private final int index;

        private ValueCarrier(RelNode node, int index) {
            this.node = node;
            this.index = index;
        }
    }
}

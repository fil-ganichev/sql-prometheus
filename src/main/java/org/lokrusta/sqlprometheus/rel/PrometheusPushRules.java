package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.util.ImmutableBitSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PrometheusPushRules {
    public static final RelOptRule JOIN_FILTER = JoinFilterPushRule.Config.INSTANCE.toRule();
    public static final RelOptRule JOIN_ON = JoinOnFilterRule.Config.INSTANCE.toRule();
    public static final RelOptRule PROJECT_FILTER = ProjectFilterPushRule.Config.INSTANCE.toRule();
    public static final RelOptRule JOIN_TIME = JoinTimeRule.Config.INSTANCE.toRule();
    public static final RelOptRule SQL_OVER_PROM = SqlOverPromAggregateRule.Config.INSTANCE.toRule();

    private PrometheusPushRules() {
    }

    private static final class JoinFilterPushRule extends RelRule<JoinFilterPushRule.Config> {
        private static final class Config extends FixedOperandConfig {
            private static final Config INSTANCE = new Config();

            private Config() {
                super("JoinFilterPushRule",
                        b0 -> b0.operand(LogicalFilter.class).oneInput(b1 ->
                                b1.operand(LogicalJoin.class).anyInputs()));
            }

            @Override
            public JoinFilterPushRule toRule() {
                return new JoinFilterPushRule(this);
            }
        }

        private JoinFilterPushRule(Config config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            LogicalFilter filter = call.rel(0);
            LogicalJoin join = call.rel(1);
            int leftCount = join.getLeft().getRowType().getFieldCount();
            RexBuilder rexBuilder = join.getCluster().getRexBuilder();
            List<RexNode> left = new ArrayList<>();
            List<RexNode> right = new ArrayList<>();
            List<RexNode> equijoin = new ArrayList<>();
            List<RexNode> above = new ArrayList<>();
            for (RexNode conjunct : RelOptUtil.conjunctions(filter.getCondition())) {
                ImmutableBitSet bits = RelOptUtil.InputFinder.bits(conjunct);
                boolean onLeft = !bits.isEmpty();
                boolean onRight = !bits.isEmpty();
                for (int bit : bits) {
                    if (bit >= leftCount) {
                        onLeft = false;
                    }
                    if (bit < leftCount) {
                        onRight = false;
                    }
                }
                if (onLeft) {
                    left.add(conjunct);
                } else if (onRight) {
                    right.add(shift(conjunct, -leftCount));
                } else if (conjunct.isA(SqlKind.EQUALS)) {
                    equijoin.add(conjunct);
                } else {
                    above.add(conjunct);
                }
            }
            if (left.isEmpty() && right.isEmpty() && equijoin.isEmpty()) {
                return;
            }
            RelNode newLeft = left.isEmpty()
                    ? join.getLeft()
                    : LogicalFilter.create(join.getLeft(), RexUtil.composeConjunction(rexBuilder, left));
            RelNode newRight = right.isEmpty()
                    ? join.getRight()
                    : LogicalFilter.create(join.getRight(), RexUtil.composeConjunction(rexBuilder, right));
            RexNode condition = join.getCondition();
            JoinRelType joinType = join.getJoinType();
            if (!equijoin.isEmpty()) {
                List<RexNode> parts = new ArrayList<>();
                parts.add(condition);
                parts.addAll(equijoin);
                condition = RexUtil.composeConjunction(rexBuilder, parts);
                joinType = JoinRelType.INNER;
            }
            RelNode newJoin = join.copy(
                    join.getTraitSet(),
                    condition,
                    newLeft,
                    newRight,
                    joinType,
                    join.isSemiJoinDone());
            if (above.isEmpty()) {
                call.transformTo(newJoin);
                return;
            }
            call.transformTo(LogicalFilter.create(
                    newJoin,
                    RexUtil.composeConjunction(rexBuilder, above)));
        }
    }

    private static final class JoinOnFilterRule extends RelRule<JoinOnFilterRule.Config> {
        private static final class Config extends FixedOperandConfig {
            private static final Config INSTANCE = new Config();

            private Config() {
                super("JoinOnFilterRule",
                        b0 -> b0.operand(LogicalJoin.class).anyInputs());
            }

            @Override
            public JoinOnFilterRule toRule() {
                return new JoinOnFilterRule(this);
            }
        }

        private JoinOnFilterRule(Config config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            LogicalJoin join = call.rel(0);
            if (join.getJoinType() != JoinRelType.INNER) {
                return;
            }
            int leftCount = join.getLeft().getRowType().getFieldCount();
            RexBuilder rexBuilder = join.getCluster().getRexBuilder();
            List<RexNode> left = new ArrayList<>();
            List<RexNode> right = new ArrayList<>();
            List<RexNode> both = new ArrayList<>();
            for (RexNode conjunct : RelOptUtil.conjunctions(join.getCondition())) {
                ImmutableBitSet bits = RelOptUtil.InputFinder.bits(conjunct);
                if (bits.isEmpty()) {
                    both.add(conjunct);
                    continue;
                }
                boolean onLeft = true;
                boolean onRight = true;
                for (int bit : bits) {
                    if (bit >= leftCount) {
                        onLeft = false;
                    }
                    if (bit < leftCount) {
                        onRight = false;
                    }
                }
                if (onLeft) {
                    left.add(conjunct);
                } else if (onRight) {
                    right.add(shift(conjunct, -leftCount));
                } else {
                    both.add(conjunct);
                }
            }
            if (left.isEmpty() && right.isEmpty()) {
                return;
            }
            RelNode newLeft = left.isEmpty()
                    ? join.getLeft()
                    : LogicalFilter.create(join.getLeft(), RexUtil.composeConjunction(rexBuilder, left));
            RelNode newRight = right.isEmpty()
                    ? join.getRight()
                    : LogicalFilter.create(join.getRight(), RexUtil.composeConjunction(rexBuilder, right));
            RexNode condition = both.isEmpty()
                    ? rexBuilder.makeLiteral(true)
                    : RexUtil.composeConjunction(rexBuilder, both);
            call.transformTo(join.copy(
                    join.getTraitSet(),
                    condition,
                    newLeft,
                    newRight,
                    join.getJoinType(),
                    join.isSemiJoinDone()));
        }
    }

    private static final class ProjectFilterPushRule extends RelRule<ProjectFilterPushRule.Config> {
        private static final class Config extends FixedOperandConfig {
            private static final Config INSTANCE = new Config();

            private Config() {
                super("ProjectFilterPushRule",
                        b0 -> b0.operand(LogicalFilter.class).oneInput(b1 ->
                                b1.operand(LogicalProject.class).anyInputs()));
            }

            @Override
            public ProjectFilterPushRule toRule() {
                return new ProjectFilterPushRule(this);
            }
        }

        private ProjectFilterPushRule(Config config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            LogicalFilter filter = call.rel(0);
            LogicalProject project = call.rel(1);
            Map<Integer, Integer> mapping = new HashMap<>();
            List<RexNode> projects = project.getProjects();
            for (int i = 0; i < projects.size(); i++) {
                if (projects.get(i) instanceof RexInputRef ref) {
                    mapping.put(i, ref.getIndex());
                }
            }
            RexNode remapped = remap(filter.getCondition(), mapping);
            if (remapped == null) {
                return;
            }
            RelNode pushed = project.copy(
                    project.getTraitSet(),
                    List.of(LogicalFilter.create(project.getInput(), remapped)));
            call.transformTo(pushed);
        }
    }

    private static final class JoinTimeRule extends RelRule<JoinTimeRule.Config> {
        private static final class Config extends FixedOperandConfig {
            private static final Config INSTANCE = new Config();

            private Config() {
                super("JoinTimeRule",
                        b0 -> b0.operand(LogicalJoin.class).anyInputs());
            }

            @Override
            public JoinTimeRule toRule() {
                return new JoinTimeRule(this);
            }
        }

        private JoinTimeRule(Config config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            LogicalJoin join = call.rel(0);
            PrometheusTableScan leftAny = findAnyScan(join.getLeft());
            PrometheusTableScan rightAny = findAnyScan(join.getRight());
            if (leftAny != null && rightAny != null && !timestampsEqual(join)) {
                throw new IllegalStateException("JOIN of " + leftAny.getPrometheusTable().metric()
                        + " and " + rightAny.getPrometheusTable().metric()
                        + " has no equality on \"timestamp\". Rows at different times multiply.\n"
                        + "Add the equality to this join:\n"
                        + "ON " + leftAny.getPrometheusTable().metric() + ".\"timestamp\" = "
                        + rightAny.getPrometheusTable().metric() + ".\"timestamp\".");
            }
            PrometheusTableScan left = findScan(join.getLeft());
            PrometheusTableScan right = findScan(join.getRight());
            if (left == null || right == null) {
                return;
            }
            Long start = left.getStartMillis() != null ? left.getStartMillis() : right.getStartMillis();
            Long end = left.getEndMillis() != null ? left.getEndMillis() : right.getEndMillis();
            String rateWindow = left.getRateWindow() != null ? left.getRateWindow() : right.getRateWindow();
            if (start == null && end == null) {
                return;
            }
            RelNode leftInput = unwrap(join.getLeft());
            RelNode rightInput = unwrap(join.getRight());
            RelNode newLeft = applyWindow(leftInput, start, end, rateWindow);
            RelNode newRight = applyWindow(rightInput, start, end, rateWindow);
            if (newLeft == leftInput && newRight == rightInput) {
                return;
            }
            call.transformTo(join.copy(
                    join.getTraitSet(),
                    join.getCondition(),
                    newLeft,
                    newRight,
                    join.getJoinType(),
                    join.isSemiJoinDone()));
        }

        private static RelNode unwrap(RelNode node) {
            while (node instanceof HepRelVertex vertex) {
                node = vertex.getCurrentRel();
            }
            return node;
        }

        private static PrometheusTableScan findScan(RelNode node) {
            node = unwrap(node);
            if (node instanceof PrometheusTableScan scan) {
                return (scan.getStartMillis() != null || scan.getEndMillis() != null) ? scan : scan;
            }
            if (node instanceof LogicalFilter filter) {
                return findScan(filter.getInput());
            }
            if (node instanceof LogicalProject project) {
                return findScan(project.getInput());
            }
            if (node instanceof LogicalJoin join) {
                PrometheusTableScan timed = findScan(join.getLeft());
                if (timed != null && (timed.getStartMillis() != null || timed.getEndMillis() != null)) {
                    return timed;
                }
                PrometheusTableScan right = findScan(join.getRight());
                if (timed != null && (right == null || right.getStartMillis() == null && right.getEndMillis() == null)) {
                    return timed;
                }
                return right != null ? right : timed;
            }
            return null;
        }

        private static PrometheusTableScan findAnyScan(RelNode node) {
            node = unwrap(node);
            if (node instanceof PrometheusTableScan scan) {
                return scan;
            }
            if (node instanceof LogicalFilter filter) {
                return findAnyScan(filter.getInput());
            }
            if (node instanceof LogicalProject project) {
                return findAnyScan(project.getInput());
            }
            if (node instanceof LogicalJoin join) {
                PrometheusTableScan left = findAnyScan(join.getLeft());
                return left != null ? left : findAnyScan(join.getRight());
            }
            return null;
        }

        private static boolean timestampsEqual(LogicalJoin join) {
            int leftCount = join.getLeft().getRowType().getFieldCount();
            for (RexNode conjunct : RelOptUtil.conjunctions(join.getCondition())) {
                if (!(conjunct instanceof RexCall call) || call.getKind() != SqlKind.EQUALS || call.getOperands().size() != 2) {
                    continue;
                }
                RexInputRef leftRef = inputRef(call.getOperands().get(0));
                RexInputRef rightRef = inputRef(call.getOperands().get(1));
                if (leftRef == null || rightRef == null) {
                    continue;
                }
                boolean leftOnLeft = leftRef.getIndex() < leftCount;
                boolean rightOnLeft = rightRef.getIndex() < leftCount;
                if (leftOnLeft == rightOnLeft) {
                    continue;
                }
                int leftIndex = leftOnLeft ? leftRef.getIndex() : rightRef.getIndex();
                int rightIndex = leftOnLeft ? rightRef.getIndex() - leftCount : leftRef.getIndex() - leftCount;
                if (isTimestamp(join.getLeft(), leftIndex) && isTimestamp(join.getRight(), rightIndex)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isTimestamp(RelNode node, int index) {
            node = unwrap(node);
            if (node instanceof PrometheusTableScan) {
                return index == 0;
            }
            if (node instanceof LogicalFilter filter) {
                return isTimestamp(filter.getInput(), index);
            }
            if (node instanceof LogicalProject project
                    && index >= 0
                    && index < project.getProjects().size()
                    && project.getProjects().get(index) instanceof RexInputRef ref) {
                return isTimestamp(project.getInput(), ref.getIndex());
            }
            if (node instanceof LogicalJoin join) {
                int leftCount = join.getLeft().getRowType().getFieldCount();
                if (index < leftCount) {
                    return isTimestamp(join.getLeft(), index);
                }
                return isTimestamp(join.getRight(), index - leftCount);
            }
            return false;
        }

        private static RexInputRef inputRef(RexNode node) {
            if (node instanceof RexInputRef ref) {
                return ref;
            }
            if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
                return inputRef(call.getOperands().get(0));
            }
            return null;
        }

        private static RelNode applyWindow(RelNode node, Long start, Long end, String rateWindow) {
            node = unwrap(node);
            if (node instanceof PrometheusTableScan scan) {
                boolean same = sameWindow(scan, start, end);
                boolean sameRange = rateWindow == null || rateWindow.equals(scan.getRateWindow());
                if (same && sameRange) {
                    return node;
                }
                PrometheusTableScan next = same ? scan : scan.withFilters(Map.of(), start, end);
                if (next.getRateWindow() == null && rateWindow != null) {
                    next = next.withRateWindow(rateWindow);
                }
                return next;
            }
            if (node instanceof LogicalJoin nested) {
                RelNode left = applyWindow(nested.getLeft(), start, end, rateWindow);
                RelNode right = applyWindow(nested.getRight(), start, end, rateWindow);
                if (left == unwrap(nested.getLeft()) && right == unwrap(nested.getRight())) {
                    return node;
                }
                return nested.copy(
                        nested.getTraitSet(),
                        nested.getCondition(),
                        left,
                        right,
                        nested.getJoinType(),
                        nested.isSemiJoinDone());
            }
            if (node instanceof LogicalProject project) {
                RelNode input = applyWindow(project.getInput(), start, end, rateWindow);
                if (input == unwrap(project.getInput())) {
                    return node;
                }
                return project.copy(project.getTraitSet(), input, project.getProjects(), project.getRowType());
            }
            if (node instanceof LogicalFilter filter) {
                RelNode input = applyWindow(filter.getInput(), start, end, rateWindow);
                if (input == unwrap(filter.getInput())) {
                    return node;
                }
                return filter.copy(filter.getTraitSet(), input, filter.getCondition());
            }
            return node;
        }

        private static boolean sameWindow(PrometheusTableScan scan, Long start, Long end) {
            return java.util.Objects.equals(scan.getStartMillis(), start)
                    && java.util.Objects.equals(scan.getEndMillis(), end);
        }
    }

    private static final class SqlOverPromAggregateRule extends RelRule<SqlOverPromAggregateRule.Config> {
        private static final class Config extends FixedOperandConfig {
            private static final Config INSTANCE = new Config();

            private Config() {
                super("SqlOverPromAggregateRule",
                        b0 -> b0.operand(LogicalAggregate.class).anyInputs());
            }

            @Override
            public SqlOverPromAggregateRule toRule() {
                return new SqlOverPromAggregateRule(this);
            }
        }

        private SqlOverPromAggregateRule(Config config) {
            super(config);
        }

        @Override
        public void onMatch(RelOptRuleCall call) {
            LogicalAggregate aggregate = call.rel(0);
            for (AggregateCall aggCall : aggregate.getAggCallList()) {
                String name = aggCall.getAggregation().getName();
                if (name == null) {
                    continue;
                }
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                if (!upper.equals("SUM") && !upper.equals("AVG") && !upper.equals("MIN")
                        && !upper.equals("MAX") && !upper.equals("COUNT")) {
                    continue;
                }
                for (int arg : aggCall.getArgList()) {
                    PrometheusTableScan scan = aggregateScan(aggregate.getInput(), arg);
                    if (scan != null && scan.getAgg() != null) {
                        throw new IllegalStateException(PrometheusMessages.foldAggregate(name, scan.getAgg(), scan));
                    }
                }
            }
        }

        private static PrometheusTableScan aggregateScan(RelNode node, int index) {
            node = unwrap(node);
            if (node instanceof PrometheusTableScan scan) {
                return index == 1 ? scan : null;
            }
            if (node instanceof LogicalFilter filter) {
                return aggregateScan(filter.getInput(), index);
            }
            if (node instanceof LogicalProject project
                    && index >= 0
                    && index < project.getProjects().size()
                    && project.getProjects().get(index) instanceof RexInputRef ref) {
                return aggregateScan(project.getInput(), ref.getIndex());
            }
            if (node instanceof LogicalJoin join) {
                int leftCount = join.getLeft().getRowType().getFieldCount();
                if (index < leftCount) {
                    return aggregateScan(join.getLeft(), index);
                }
                return aggregateScan(join.getRight(), index - leftCount);
            }
            return null;
        }

        private static RelNode unwrap(RelNode node) {
            while (node instanceof HepRelVertex vertex) {
                node = vertex.getCurrentRel();
            }
            return node;
        }
    }

    private static RexNode shift(RexNode node, int offset) {
        return node.accept(new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef inputRef) {
                return new RexInputRef(inputRef.getIndex() + offset, inputRef.getType());
            }
        });
    }

    private static RexNode remap(RexNode node, Map<Integer, Integer> mapping) {
        try {
            return node.accept(new RexShuttle() {
                @Override
                public RexNode visitInputRef(RexInputRef inputRef) {
                    Integer target = mapping.get(inputRef.getIndex());
                    if (target == null) {
                        throw new IllegalArgumentException("computed column");
                    }
                    return new RexInputRef(target, inputRef.getType());
                }
            });
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}

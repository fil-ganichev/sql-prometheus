package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexBuilder;
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
    public static final RelOptRule PROJECT_FILTER = ProjectFilterPushRule.Config.INSTANCE.toRule();

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
                } else {
                    above.add(conjunct);
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
            RelNode newJoin = join.copy(
                    join.getTraitSet(),
                    join.getCondition(),
                    newLeft,
                    newRight,
                    join.getJoinType(),
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

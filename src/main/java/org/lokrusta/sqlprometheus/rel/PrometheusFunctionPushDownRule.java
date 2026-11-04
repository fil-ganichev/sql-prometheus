package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexShuttle;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public class PrometheusFunctionPushDownRule extends RelRule<RelRule.Config> {
    private static final class Config extends FixedOperandConfig {
        private static final Config INSTANCE = new Config();

        private Config() {
            super("PrometheusFunctionPushDownRule",
                    b0 -> b0.operand(LogicalProject.class).anyInputs());
        }

        @Override
        public PrometheusFunctionPushDownRule toRule() {
            return new PrometheusFunctionPushDownRule(this);
        }
    }

    public static final PrometheusFunctionPushDownRule INSTANCE = Config.INSTANCE.toRule();

    private PrometheusFunctionPushDownRule(RelRule.Config config) {
        super(config);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalProject project = call.rel(0);
        Rewriter rewriter = new Rewriter(project.getInput());
        List<RexNode> projects = new ArrayList<>();
        for (RexNode expression : project.getProjects()) {
            projects.add(expression.accept(rewriter));
        }
        if (!rewriter.saw || rewriter.conflict || rewriter.assignments.isEmpty()) {
            return;
        }
        Map<PrometheusTableScan, PrometheusTableScan> replacements = new IdentityHashMap<>();
        rewriter.assignments.forEach((scan, assignment) ->
                replacements.put(scan, scan.withFunction(assignment.function, assignment.window)));
        call.transformTo(project.copy(
                project.getTraitSet(),
                replaceScans(project.getInput(), replacements),
                projects,
                project.getRowType()));
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
            return new Target(scan, index);
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

    private static RelNode replaceScans(RelNode node, Map<PrometheusTableScan, PrometheusTableScan> replacements) {
        node = unwrap(node);
        if (node instanceof PrometheusTableScan scan) {
            PrometheusTableScan pushed = replacements.get(scan);
            return pushed != null ? pushed : node;
        }
        if (node instanceof LogicalFilter filter) {
            RelNode input = replaceScans(filter.getInput(), replacements);
            if (input == unwrap(filter.getInput())) {
                return node;
            }
            return filter.copy(filter.getTraitSet(), input, filter.getCondition());
        }
        if (node instanceof LogicalProject project) {
            RelNode input = replaceScans(project.getInput(), replacements);
            if (input == unwrap(project.getInput())) {
                return node;
            }
            return project.copy(project.getTraitSet(), input, project.getProjects(), project.getRowType());
        }
        if (node instanceof LogicalJoin join) {
            RelNode left = replaceScans(join.getLeft(), replacements);
            RelNode right = replaceScans(join.getRight(), replacements);
            if (left == unwrap(join.getLeft()) && right == unwrap(join.getRight())) {
                return node;
            }
            return join.copy(
                    join.getTraitSet(),
                    join.getCondition(),
                    left,
                    right,
                    join.getJoinType(),
                    join.isSemiJoinDone());
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

    private static final class Assignment {
        private final String function;
        private final String window;

        private Assignment(String function, String window) {
            this.function = function;
            this.window = window;
        }
    }

    private static final class Rewriter extends RexShuttle {
        private final RelNode input;
        private final Map<PrometheusTableScan, Assignment> assignments = new IdentityHashMap<>();
        private boolean saw;
        private boolean conflict;

        private Rewriter(RelNode input) {
            this.input = input;
        }

        @Override
        public RexNode visitCall(RexCall call) {
            String promql = PrometheusSqlOperatorTable.promqlName(call.getOperator().getName());
            if (promql == null) {
                return super.visitCall(call);
            }
            RexInputRef value = call.getOperands().isEmpty() ? null : valueRef(call.getOperands().get(0));
            String literal = call.getOperands().size() == 2 ? windowOf(call.getOperands().get(1)) : null;
            Target target = value == null ? null : resolve(input, value.getIndex());
            if (literal == null || target == null || target.column != 1 || target.scan.getPromFunction() != null) {
                conflict = true;
                return super.visitCall(call);
            }
            Assignment existing = assignments.get(target.scan);
            if (existing == null) {
                assignments.put(target.scan, new Assignment(promql, literal));
            } else if (!existing.function.equals(promql) || !existing.window.equals(literal)) {
                conflict = true;
            }
            saw = true;
            return value;
        }
    }

    private static RexInputRef valueRef(RexNode node) {
        if (node instanceof RexInputRef ref) {
            return ref;
        }
        if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
            return valueRef(call.getOperands().get(0));
        }
        return null;
    }

    private static String windowOf(RexNode node) {
        if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
            return windowOf(call.getOperands().get(0));
        }
        if (!(node instanceof RexLiteral literal) || literal.getTypeName().getFamily() != SqlTypeFamily.CHARACTER) {
            return null;
        }
        String text = RexLiteral.stringValue(literal);
        return PrometheusSqlOperatorTable.validWindow(text) ? text : null;
    }
}

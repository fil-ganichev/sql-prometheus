package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexUtil;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PrometheusFilterRule extends RelRule<RelRule.Config> {
    private static final class Config extends FixedOperandConfig {
        private static final Config INSTANCE = new Config();

        private Config() {
            super("PrometheusFilterRule",
                    b0 -> b0.operand(LogicalFilter.class).oneInput(b1 ->
                            b1.operand(PrometheusTableScan.class).noInputs()));
        }

        @Override
        public PrometheusFilterRule toRule() {
            return new PrometheusFilterRule(this);
        }
    }

    public static final PrometheusFilterRule INSTANCE = Config.INSTANCE.toRule();

    private PrometheusFilterRule(RelRule.Config config) {
        super(config);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        LogicalFilter filter = call.rel(0);
        PrometheusTableScan scan = call.rel(1);
        List<RexNode> conjuncts = RelOptUtil.conjunctions(filter.getCondition());
        Map<String, String> labels = new LinkedHashMap<>();
        Long start = scan.getStartMillis();
        Long end = scan.getEndMillis();
        List<RexNode> remaining = new ArrayList<>();
        List<String> names = scan.getRowType().getFieldNames();

        for (RexNode conjunct : conjuncts) {
            if (!absorb(conjunct, names, labels)) {
                remaining.add(conjunct);
                continue;
            }
            TimeBound bound = boundOf(conjunct);
            if (bound != null) {
                start = bound.start != null ? bound.start : start;
                end = bound.end != null ? bound.end : end;
            }
        }
        if (remaining.size() == conjuncts.size()) {
            return;
        }

        PrometheusTableScan pushed = scan.withFilters(labels, start, end);
        if (remaining.isEmpty()) {
            call.transformTo(pushed);
            return;
        }
        RexBuilder rexBuilder = filter.getCluster().getRexBuilder();
        call.transformTo(filter.copy(
                filter.getTraitSet(),
                pushed,
                RexUtil.composeConjunction(rexBuilder, remaining)));
    }

    private static boolean absorb(RexNode conjunct, List<String> names, Map<String, String> labels) {
        if (!(conjunct instanceof RexCall call) || call.getOperands().size() != 2) {
            return false;
        }
        Side left = side(call.getOperands().get(0));
        Side right = side(call.getOperands().get(1));
        Side refSide = left.ref != null ? left : right;
        Side literalSide = left.literal != null ? left : right;
        if (refSide.ref == null || literalSide.literal == null) {
            return false;
        }
        int index = refSide.ref.getIndex();
        if (call.getKind() == SqlKind.EQUALS && index >= 2 && isChar(literalSide.literal)) {
            labels.put(names.get(index), RexLiteral.stringValue(literalSide.literal));
            return true;
        }
        return index == 0 && isTimeKind(call.getKind()) && isNumber(literalSide.literal);
    }

    private static TimeBound boundOf(RexNode conjunct) {
        if (!(conjunct instanceof RexCall call) || call.getOperands().size() != 2) {
            return null;
        }
        Side left = side(call.getOperands().get(0));
        Side right = side(call.getOperands().get(1));
        boolean refOnLeft = left.ref != null && left.ref.getIndex() == 0;
        RexLiteral literal = refOnLeft ? right.literal : left.literal;
        if (literal == null || !isNumber(literal)) {
            return null;
        }
        long value = longValue(literal);
        SqlKind kind = call.getKind();
        if (!refOnLeft) {
            kind = kind.reverse();
        }
        return switch (kind) {
            case EQUALS -> new TimeBound(value, value);
            case GREATER_THAN, GREATER_THAN_OR_EQUAL -> new TimeBound(value, null);
            case LESS_THAN, LESS_THAN_OR_EQUAL -> new TimeBound(null, value);
            default -> null;
        };
    }

    private static boolean isTimeKind(SqlKind kind) {
        return kind == SqlKind.EQUALS
                || kind == SqlKind.GREATER_THAN
                || kind == SqlKind.GREATER_THAN_OR_EQUAL
                || kind == SqlKind.LESS_THAN
                || kind == SqlKind.LESS_THAN_OR_EQUAL;
    }

    private static Side side(RexNode node) {
        return new Side(inputRef(node), literal(node));
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

    private static RexLiteral literal(RexNode node) {
        if (node instanceof RexLiteral literal) {
            return literal;
        }
        if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
            return literal(call.getOperands().get(0));
        }
        return null;
    }

    private static boolean isChar(RexLiteral literal) {
        SqlTypeName typeName = literal.getTypeName();
        return typeName == SqlTypeName.CHAR || typeName == SqlTypeName.VARCHAR;
    }

    private static boolean isNumber(RexLiteral literal) {
        return SqlTypeName.NUMERIC_TYPES.contains(literal.getTypeName());
    }

    private static long longValue(RexLiteral literal) {
        Object value = literal.getValue2();
        if (value instanceof BigDecimal decimal) {
            return decimal.longValue();
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    private static final class Side {
        private final RexInputRef ref;
        private final RexLiteral literal;

        private Side(RexInputRef ref, RexLiteral literal) {
            this.ref = ref;
            this.literal = literal;
        }
    }

    private static final class TimeBound {
        private final Long start;
        private final Long end;

        private TimeBound(Long start, Long end) {
            this.start = start;
            this.end = end;
        }
    }
}

package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelRule;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.logical.LogicalAggregate;
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
import org.lokrusta.sqlprometheus.exec.PrometheusCallShape;
import org.lokrusta.sqlprometheus.sql.PrometheusSqlOperatorTable;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

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
        rewriter.assignments.forEach((scan, assignment) -> {
            List<String> by = assignment.agg == null ? List.of() : aggregateBy(scan, project);
            replacements.put(scan, scan.withCall(
                    assignment.function,
                    assignment.window,
                    assignment.args,
                    assignment.agg,
                    by,
                    assignment.innerScalars,
                    assignment.scalars,
                    assignment.shape));
        });
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
        if (node instanceof LogicalAggregate aggregate) {
            int groupCount = aggregate.getGroupCount();
            if (index < groupCount || index - groupCount >= aggregate.getAggCallList().size()) {
                return null;
            }
            AggregateCall aggCall = aggregate.getAggCallList().get(index - groupCount);
            SqlKind kind = aggCall.getAggregation().getKind();
            if ((kind != SqlKind.MAX && kind != SqlKind.MIN) || aggCall.getArgList().size() != 1) {
                return null;
            }
            Target inner = resolve(aggregate.getInput(), aggCall.getArgList().get(0));
            if (inner != null && inner.column == 1 && inner.scan.getQuantilePhi() != null) {
                return inner;
            }
            return null;
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
        if (node instanceof LogicalAggregate aggregate) {
            RelNode input = replaceScans(aggregate.getInput(), replacements);
            if (input == unwrap(aggregate.getInput())) {
                return node;
            }
            return aggregate.copy(
                    aggregate.getTraitSet(),
                    input,
                    aggregate.getGroupSet(),
                    aggregate.getGroupSets(),
                    aggregate.getAggCallList());
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
        private final List<String> args;
        private final String agg;
        private final List<String> innerScalars;
        private final List<String> scalars;
        private final PrometheusCallShape shape;

        private Assignment(String function,
                           String window,
                           List<String> args,
                           String agg,
                           List<String> innerScalars,
                           List<String> scalars,
                           PrometheusCallShape shape) {
            this.function = function;
            this.window = window;
            this.args = args;
            this.agg = agg;
            this.innerScalars = innerScalars;
            this.scalars = scalars;
            this.shape = shape == null ? PrometheusCallShape.EMPTY : shape;
        }

        private boolean matches(Assignment other) {
            return Objects.equals(function, other.function)
                    && Objects.equals(window, other.window)
                    && args.equals(other.args)
                    && Objects.equals(agg, other.agg)
                    && innerScalars.equals(other.innerScalars)
                    && scalars.equals(other.scalars)
                    && shape.equals(other.shape);
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
            Parsed parsed = parse(call);
            if (parsed == null) {
                return super.visitCall(call);
            }
            Target target = resolve(input, parsed.value.getIndex());
            boolean pushedFunction = parsed.function != null;
            boolean pushedAggregate = parsed.agg != null;
            boolean pushedShape = parsed.shaped();
            if (parsed.whenCompare != null && (target == null || target.column != 1)) {
                throw new IllegalStateException(
                        parsed.sqlName + " compares the value column with a numeric literal");
            }
            if (target == null
                    || target.column != 1
                    || (pushedFunction && parsed.window == null)
                    || (pushedFunction && target.scan.getPromFunction() != null)
                    || (pushedFunction && target.scan.getQuantilePhi() != null)
                    || (pushedAggregate && target.scan.getAgg() != null)
                    || (pushedAggregate && target.scan.getQuantilePhi() != null)
                    || (pushedShape && !target.scan.getShape().isEmpty())) {
                if (pushedFunction || pushedAggregate || pushedShape) {
                    conflict = true;
                }
                return super.visitCall(call);
            }
            if (pushedFunction && parsed.sqlName != null) {
                rejectValuePredicate(input, target.scan, parsed.sqlName);
            }
            if (parsed.labelDst != null) {
                List<String> labels = target.scan.getPrometheusTable().labelColumns();
                if (!labels.contains(parsed.labelDst) || !labels.contains(parsed.labelSrc)) {
                    throw new IllegalStateException(
                            "PROM_LABEL_REPLACE destination and source must be labels that already belong to the metric");
                }
            }
            String window = parsed.window;
            List<String> args = parsed.args;
            if (parsed.whenCompare != null) {
                String step = parsed.whenStep != null
                        ? parsed.whenStep
                        : target.scan.getPrometheusTable().options().step();
                long stepMillis = PrometheusSqlOperatorTable.durationMillis(step);
                long windowMillis = PrometheusSqlOperatorTable.durationMillis(parsed.window);
                if (stepMillis < 0 || windowMillis < 0 || stepMillis >= windowMillis) {
                    throw new IllegalStateException(parsed.sqlName + " step " + step
                            + " must be shorter than the rate window " + parsed.window);
                }
                window = parsed.window + ":" + step;
                args = List.of(parsed.whenExtra == null || parsed.whenExtra.isEmpty()
                        ? parsed.whenCompare
                        : parsed.whenCompare + ";" + parsed.whenExtra);
            }
            String outerWindow = parsed.outerWindow;
            if (parsed.outerFunction != null) {
                String step = parsed.outerStep != null
                        ? parsed.outerStep
                        : target.scan.getPrometheusTable().options().step();
                long stepMillis = PrometheusSqlOperatorTable.durationMillis(step);
                long windowMillis = PrometheusSqlOperatorTable.durationMillis(parsed.outerWindow);
                if (stepMillis < 0 || windowMillis < 0 || stepMillis >= windowMillis) {
                    throw new IllegalStateException(parsed.outerSqlName + " step " + step
                            + " must be shorter than the subquery window " + parsed.outerWindow);
                }
                outerWindow = parsed.outerWindow + ":" + step;
            }
            PrometheusCallShape shape = new PrometheusCallShape(
                    parsed.offset,
                    parsed.rank,
                    parsed.rankCount,
                    parsed.outerFunction,
                    outerWindow,
                    parsed.labelDst,
                    parsed.labelRepl,
                    parsed.labelSrc,
                    parsed.labelRegex,
                    parsed.pipe);
            Assignment assignment = new Assignment(
                    parsed.function, window, args, parsed.agg, parsed.innerScalars, parsed.scalars, shape);
            Assignment existing = assignments.get(target.scan);
            if (existing == null) {
                assignments.put(target.scan, assignment);
            } else if (!existing.matches(assignment)) {
                conflict = true;
            }
            saw = true;
            return parsed.value;
        }
    }

    private static Parsed parse(RexCall call) {
        List<String> pipe = new ArrayList<>();
        Modifiers modifiers = new Modifiers();
        List<String> scalars = new ArrayList<>();
        RexNode cursor = peelModifiers(call, scalars, pipe, modifiers);
        String agg = null;
        if (cursor instanceof RexCall current) {
            agg = PrometheusSqlOperatorTable.promqlAggregate(current.getOperator().getName());
            if (agg != null) {
                if (current.getOperands().size() != 1) {
                    return null;
                }
                pipe.add("a");
                cursor = unwrapCast(current.getOperands().get(0));
                if (cursor instanceof RexCall nested
                        && PrometheusSqlOperatorTable.promqlAggregate(nested.getOperator().getName()) != null) {
                    throw new IllegalStateException("Prometheus aggregate cannot wrap another Prometheus aggregate");
                }
            }
        }
        List<String> innerScalars = new ArrayList<>();
        if (agg != null) {
            cursor = peelModifiers(cursor, innerScalars, pipe, modifiers);
        }
        if (cursor instanceof RexCall current) {
            RexCall inner = subqueryInner(current, modifiers);
            if (inner != null) {
                readSubqueryBounds(current, modifiers);
                cursor = inner;
            }
        }
        String function = null;
        String window = null;
        List<String> args = List.of();
        RexInputRef value = null;
        String sqlName = null;
        String whenCompare = null;
        String whenStep = null;
        String whenExtra = null;
        if (cursor instanceof RexCall current) {
            String operator = current.getOperator().getName();
            if (operator != null && operator.toUpperCase(Locale.ROOT).endsWith("_WHEN")) {
                WhenCall when = parseWhen(current);
                function = when.promql;
                value = when.value;
                window = when.window;
                sqlName = when.sqlName;
                whenCompare = when.compare;
                whenStep = when.step;
                whenExtra = when.extra;
            } else {
                function = PrometheusSqlOperatorTable.promqlName(operator);
                if (function != null) {
                    sqlName = operator.toUpperCase(Locale.ROOT);
                    RexNode sample = current.getOperands().isEmpty()
                            ? null
                            : peelOffsetOnly(unwrapCast(current.getOperands().get(0)), modifiers);
                    value = sample == null ? null : valueRef(sample);
                    window = current.getOperands().size() >= 2 ? windowOf(current.getOperands().get(1)) : null;
                    args = arguments(current);
                    if ("predict_linear".equals(function) && window != null && args.size() != 1) {
                        throw new IllegalStateException("PROM_PREDICT_LINEAR seconds must be a numeric literal");
                    }
                    if ("holt_winters".equals(function) && window != null && !holtFactors(args)) {
                        throw new IllegalStateException("PROM_HOLT_WINTERS factors must be numeric literals between 0 and 1");
                    }
                    if ("quantile_over_time".equals(function) && (args.size() != 1 || !unitInterval(args.get(0)))) {
                        throw new IllegalStateException(
                                "PROM_QUANTILE_OVER_TIME phi must be a numeric literal between 0 and 1");
                    }
                    if (function.endsWith("_over_time")
                            && !"quantile_over_time".equals(function)
                            && modifiers.outerFunction == null
                            && current.getOperands().size() >= 3
                            && !omitted(current.getOperands().get(2))) {
                        throw new IllegalStateException(sqlName
                                + " step is only used when the first argument is a Prometheus window function");
                    }
                }
            }
        } else if (cursor instanceof RexInputRef ref) {
            value = ref;
        }
        if (function != null && function.endsWith("_over_time") && value == null) {
            throw new IllegalStateException(sqlName
                    + " reads the value column, or wraps one Prometheus window function");
        }
        if (value == null || (function == null && agg == null && scalars.isEmpty() && innerScalars.isEmpty() && !modifiers.shaped())) {
            return null;
        }
        Collections.reverse(scalars);
        Collections.reverse(innerScalars);
        String pipeSpec = modifiers.rank != null || modifiers.labelDst != null ? String.join(",", pipe) : null;
        return new Parsed(value, function, window, args, agg, List.copyOf(innerScalars), List.copyOf(scalars),
                sqlName, whenCompare, whenStep, whenExtra,
                modifiers.offset, modifiers.rank, modifiers.rankCount,
                modifiers.outerFunction, modifiers.outerWindow, modifiers.outerStep, modifiers.outerSqlName,
                modifiers.labelDst, modifiers.labelRepl, modifiers.labelSrc, modifiers.labelRegex, pipeSpec);
    }

    private static RexNode peelModifiers(RexNode cursor, List<String> scalars, List<String> pipe, Modifiers modifiers) {
        while (cursor instanceof RexCall current) {
            String operator = current.getOperator().getName().toUpperCase(Locale.ROOT);
            if ("PROM_OFFSET".equals(operator)) {
                cursor = peelOffsetOnly(current, modifiers);
                continue;
            }
            if ("PROM_TOPK".equals(operator) || "PROM_BOTTOMK".equals(operator)) {
                if (modifiers.rank != null) {
                    throw new IllegalStateException("PROM_TOPK and PROM_BOTTOMK cannot be nested");
                }
                if (current.getOperands().size() != 2) {
                    throw new IllegalStateException(operator + " count must be a positive integer");
                }
                String count = positiveInt(current.getOperands().get(0));
                if (count == null) {
                    throw new IllegalStateException(operator + " count must be a positive integer");
                }
                modifiers.rank = "PROM_TOPK".equals(operator) ? "topk" : "bottomk";
                modifiers.rankCount = count;
                pipe.add("k");
                cursor = unwrapCast(current.getOperands().get(1));
                continue;
            }
            if ("PROM_LABEL_REPLACE".equals(operator)) {
                if (modifiers.labelDst != null) {
                    throw new IllegalStateException("PROM_LABEL_REPLACE cannot be nested");
                }
                String[] label = labelArgs(current);
                modifiers.labelDst = label[0];
                modifiers.labelRepl = label[1];
                modifiers.labelSrc = label[2];
                modifiers.labelRegex = label[3];
                pipe.add("l");
                cursor = unwrapCast(current.getOperands().get(0));
                continue;
            }
            String scalar = scalarName(current);
            if (scalar == null) {
                break;
            }
            scalars.add(scalar);
            pipe.add("s:" + scalar);
            cursor = unwrapCast(current.getOperands().get(0));
        }
        return cursor;
    }

    private static RexNode peelOffsetOnly(RexNode node, Modifiers modifiers) {
        node = unwrapCast(node);
        if (!(node instanceof RexCall call) || !"PROM_OFFSET".equals(call.getOperator().getName().toUpperCase(Locale.ROOT))) {
            return node;
        }
        if (modifiers.offset != null) {
            throw new IllegalStateException("PROM_OFFSET is already set");
        }
        if (call.getOperands().size() != 2) {
            throw new IllegalStateException("PROM_OFFSET shift must be a duration literal, for example '1w'");
        }
        String shift = windowOf(call.getOperands().get(1));
        if (shift == null) {
            throw new IllegalStateException("PROM_OFFSET shift must be a duration literal, for example '1w'");
        }
        modifiers.offset = shift;
        return unwrapCast(call.getOperands().get(0));
    }

    private static RexCall subqueryInner(RexCall call, Modifiers modifiers) {
        String sqlName = call.getOperator().getName().toUpperCase(Locale.ROOT);
        if (!sqlName.endsWith("_OVER_TIME") || "PROM_QUANTILE_OVER_TIME".equals(sqlName) || sqlName.endsWith("_WHEN")) {
            return null;
        }
        if (call.getOperands().isEmpty()) {
            return null;
        }
        Modifiers trial = new Modifiers();
        RexNode first = peelOffsetOnly(unwrapCast(call.getOperands().get(0)), trial);
        if (!(first instanceof RexCall inner) || !isRangeCall(inner)) {
            return null;
        }
        if (subqueryInner(inner, new Modifiers()) != null) {
            throw new IllegalStateException("Prometheus subquery cannot wrap another subquery");
        }
        if (trial.offset != null) {
            if (modifiers.offset != null) {
                throw new IllegalStateException("PROM_OFFSET is already set");
            }
            modifiers.offset = trial.offset;
        }
        return inner;
    }

    private static boolean isRangeCall(RexCall call) {
        String name = call.getOperator().getName();
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.endsWith("_WHEN")) {
            return PrometheusSqlOperatorTable.promqlName(upper.substring(0, upper.length() - "_WHEN".length())) != null;
        }
        return PrometheusSqlOperatorTable.promqlName(upper) != null;
    }

    private static void readSubqueryBounds(RexCall call, Modifiers modifiers) {
        String sqlName = call.getOperator().getName().toUpperCase(Locale.ROOT);
        String promql = PrometheusSqlOperatorTable.promqlName(sqlName);
        if (promql == null) {
            throw new IllegalStateException(sqlName + " window must be a duration literal, for example '1h'");
        }
        String window = call.getOperands().size() > 1 ? windowOf(call.getOperands().get(1)) : null;
        if (window == null) {
            throw new IllegalStateException(sqlName + " window must be a duration literal, for example '1h'");
        }
        int size = call.getOperands().size();
        if (size == 3 && omitted(call.getOperands().get(2))) {
            size = 2;
        }
        if (size > 3) {
            throw new IllegalStateException(sqlName + " step must be a duration literal, for example '1m'");
        }
        String step = null;
        if (size == 3) {
            step = windowOf(call.getOperands().get(2));
            if (step == null) {
                throw new IllegalStateException(sqlName + " step must be a duration literal, for example '1m'");
            }
        }
        modifiers.outerFunction = promql;
        modifiers.outerWindow = window;
        modifiers.outerStep = step;
        modifiers.outerSqlName = sqlName;
    }

    private static String[] labelArgs(RexCall call) {
        if (call.getOperands().size() != 5) {
            throw new IllegalStateException(
                    "PROM_LABEL_REPLACE destination, replacement, source and regex must be string literals");
        }
        String destination = textOf(call.getOperands().get(1));
        String replacement = textOf(call.getOperands().get(2));
        String source = textOf(call.getOperands().get(3));
        String regex = textOf(call.getOperands().get(4));
        if (destination == null || destination.isEmpty() || replacement == null || source == null || source.isEmpty() || regex == null) {
            throw new IllegalStateException(
                    "PROM_LABEL_REPLACE destination, replacement, source and regex must be string literals");
        }
        return new String[]{destination, replacement, source, regex};
    }

    private static WhenCall parseWhen(RexCall call) {
        String sqlName = call.getOperator().getName().toUpperCase(Locale.ROOT);
        String promql = PrometheusSqlOperatorTable.promqlName(sqlName.substring(0, sqlName.length() - "_WHEN".length()));
        if (promql == null || call.getOperands().isEmpty()) {
            throw new IllegalStateException(sqlName + " compares the value column with a numeric literal");
        }
        Comparison comparison = comparisonOf(call.getOperands().get(0));
        if (comparison == null) {
            throw new IllegalStateException(sqlName + " compares the value column with a numeric literal");
        }
        String window = call.getOperands().size() > 1 ? windowOf(call.getOperands().get(1)) : null;
        if (window == null) {
            throw new IllegalStateException(sqlName + " window must be a duration literal, for example '5m'");
        }
        int extras = switch (sqlName) {
            case "PROM_PREDICT_LINEAR_WHEN", "PROM_QUANTILE_OVER_TIME_WHEN" -> 1;
            case "PROM_HOLT_WINTERS_WHEN" -> 2;
            default -> 0;
        };
        int size = call.getOperands().size();
        if (size == 3 + extras && omitted(call.getOperands().get(2 + extras))) {
            size = 2 + extras;
        }
        if (size < 2 + extras || size > 3 + extras) {
            throw new IllegalStateException(sqlName + " step must be a duration literal, for example '15s'");
        }
        List<String> extraArgs = new ArrayList<>();
        for (int i = 0; i < extras; i++) {
            String number = numberOf(call.getOperands().get(2 + i));
            if (number == null) {
                throw new IllegalStateException(sqlName + " extra arguments must be numeric literals");
            }
            extraArgs.add(number);
        }
        if ("PROM_HOLT_WINTERS_WHEN".equals(sqlName) && !holtFactors(extraArgs)) {
            throw new IllegalStateException("PROM_HOLT_WINTERS factors must be numeric literals between 0 and 1");
        }
        if ("PROM_QUANTILE_OVER_TIME_WHEN".equals(sqlName) && (extraArgs.size() != 1 || !unitInterval(extraArgs.get(0)))) {
            throw new IllegalStateException("PROM_QUANTILE_OVER_TIME phi must be a numeric literal between 0 and 1");
        }
        String step = null;
        if (size == 3 + extras) {
            step = windowOf(call.getOperands().get(2 + extras));
            if (step == null) {
                throw new IllegalStateException(sqlName + " step must be a duration literal, for example '15s'");
            }
        }
        String extra = extraArgs.isEmpty() ? null : String.join(", ", extraArgs);
        return new WhenCall(comparison.value, promql, window, comparison.promql, step, extra, sqlName);
    }

    private static boolean omitted(RexNode node) {
        node = unwrapCast(node);
        if (node instanceof RexCall call && call.getKind() == SqlKind.DEFAULT) {
            return true;
        }
        return node instanceof RexLiteral literal && literal.isNull();
    }

    private static Comparison comparisonOf(RexNode node) {
        node = unwrapCast(node);
        if (!(node instanceof RexCall call) || call.getOperands().size() != 2) {
            return null;
        }
        RexNode left = unwrapCast(call.getOperands().get(0));
        RexNode right = unwrapCast(call.getOperands().get(1));
        RexInputRef value;
        String number;
        boolean literalOnLeft;
        if (left instanceof RexInputRef leftRef && (number = numberOf(right)) != null) {
            value = leftRef;
            literalOnLeft = false;
        } else if (right instanceof RexInputRef rightRef && (number = numberOf(left)) != null) {
            value = rightRef;
            literalOnLeft = true;
        } else {
            return null;
        }
        String operator = switch (call.getKind()) {
            case GREATER_THAN -> literalOnLeft ? "<" : ">";
            case GREATER_THAN_OR_EQUAL -> literalOnLeft ? "<=" : ">=";
            case LESS_THAN -> literalOnLeft ? ">" : "<";
            case LESS_THAN_OR_EQUAL -> literalOnLeft ? ">=" : "<=";
            case EQUALS -> "==";
            default -> null;
        };
        if (operator == null) {
            return null;
        }
        return new Comparison(value, operator + " " + number);
    }

    private static void rejectValuePredicate(RelNode node, PrometheusTableScan scan, String sqlName) {
        node = unwrap(node);
        if (node instanceof LogicalFilter filter) {
            if (mentionsValue(filter.getCondition(), filter.getInput(), scan)) {
                throw new IllegalStateException(valueFilterMessage(sqlName));
            }
            rejectValuePredicate(filter.getInput(), scan, sqlName);
        } else if (node instanceof LogicalProject project) {
            rejectValuePredicate(project.getInput(), scan, sqlName);
        } else if (node instanceof LogicalJoin join) {
            rejectValuePredicate(join.getLeft(), scan, sqlName);
            rejectValuePredicate(join.getRight(), scan, sqlName);
        } else if (node instanceof LogicalAggregate aggregate) {
            rejectValuePredicate(aggregate.getInput(), scan, sqlName);
        }
    }

    private static boolean mentionsValue(RexNode expression, RelNode scope, PrometheusTableScan scan) {
        boolean[] found = {false};
        expression.accept(new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef ref) {
                Target target = resolve(scope, ref.getIndex());
                if (target != null && target.scan == scan && target.column == 1) {
                    found[0] = true;
                }
                return ref;
            }
        });
        return found[0];
    }

    private static String valueFilterMessage(String sqlName) {
        String name = sqlName.toUpperCase(Locale.ROOT);
        if (name.endsWith("_WHEN")) {
            String plain = name.substring(0, name.length() - "_WHEN".length());
            return name + " already compares \"value\" before the function. "
                    + "A WHERE predicate on \"value\" would filter the " + plain + " result a second time. "
                    + "Remove that predicate, or filter an alias of the result.";
        }
        return name + " cannot share a scan with a predicate on \"value\".\n"
                + "That predicate would filter the function result, not the raw samples.\n"
                + "To keep results greater than 1, filter the alias:\n"
                + "  SELECT rate_value FROM (SELECT " + name + "(\"value\", '5m') AS rate_value FROM metric) t WHERE rate_value > 1\n"
                + "To drop raw samples before the function, use " + name + "_WHEN(\"value\" > 1, '5m').\n"
                + "The connection step becomes the subquery resolution unless the call sets its own.";
    }

    private static List<String> arguments(RexCall call) {
        if (call.getOperands().size() <= 2) {
            return List.of();
        }
        List<String> args = new ArrayList<>();
        for (int i = 2; i < call.getOperands().size(); i++) {
            String number = numberOf(call.getOperands().get(i));
            if (number == null) {
                return List.of();
            }
            args.add(number);
        }
        return args;
    }

    private static boolean holtFactors(List<String> args) {
        return args.size() == 2 && unitInterval(args.get(0)) && unitInterval(args.get(1));
    }

    private static boolean unitInterval(String text) {
        try {
            BigDecimal value = new BigDecimal(text);
            return value.compareTo(BigDecimal.ZERO) >= 0 && value.compareTo(BigDecimal.ONE) <= 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static List<String> aggregateBy(PrometheusTableScan scan, LogicalProject project) {
        Set<String> needed = new LinkedHashSet<>();
        collect(project, scan, needed);
        List<String> ordered = new ArrayList<>();
        for (String label : scan.getPrometheusTable().labelColumns()) {
            if (needed.contains(label)) {
                ordered.add(label);
            }
        }
        return ordered;
    }

    private static void collect(RelNode node, PrometheusTableScan scan, Set<String> needed) {
        node = unwrap(node);
        if (node instanceof LogicalFilter filter) {
            collectExpr(filter.getCondition(), filter, scan, needed);
            collect(filter.getInput(), scan, needed);
        } else if (node instanceof LogicalProject project) {
            for (RexNode expression : project.getProjects()) {
                collectExpr(expression, project.getInput(), scan, needed);
            }
            collect(project.getInput(), scan, needed);
        } else if (node instanceof LogicalJoin join) {
            collectExpr(join.getCondition(), join, scan, needed);
            collect(join.getLeft(), scan, needed);
            collect(join.getRight(), scan, needed);
        } else if (node instanceof LogicalAggregate aggregate) {
            for (int index : aggregate.getGroupSet()) {
                addLabel(resolve(aggregate.getInput(), index), scan, needed);
            }
            collect(aggregate.getInput(), scan, needed);
        }
    }

    private static void collectExpr(RexNode expression, RelNode scope, PrometheusTableScan scan, Set<String> needed) {
        expression.accept(new RexShuttle() {
            @Override
            public RexNode visitInputRef(RexInputRef ref) {
                addLabel(resolve(scope, ref.getIndex()), scan, needed);
                return ref;
            }
        });
    }

    private static void addLabel(Target target, PrometheusTableScan scan, Set<String> needed) {
        if (target == null || target.scan != scan || target.column < 2) {
            return;
        }
        needed.add(target.scan.getRowType().getFieldList().get(target.column).getName());
    }

    private static String scalarName(RexCall call) {
        if (call.getOperands().size() != 1) {
            return null;
        }
        return switch (call.getOperator().getName().toUpperCase(Locale.ROOT)) {
            case "ABS" -> "abs";
            case "CEIL", "CEILING" -> "ceil";
            case "FLOOR" -> "floor";
            case "ROUND" -> "round";
            case "LN" -> "ln";
            case "LOG10" -> "log10";
            case "SQRT" -> "sqrt";
            default -> null;
        };
    }

    private static final class Parsed {
        private final RexInputRef value;
        private final String function;
        private final String window;
        private final List<String> args;
        private final String agg;
        private final List<String> innerScalars;
        private final List<String> scalars;
        private final String sqlName;
        private final String whenCompare;
        private final String whenStep;
        private final String whenExtra;
        private final String offset;
        private final String rank;
        private final String rankCount;
        private final String outerFunction;
        private final String outerWindow;
        private final String outerStep;
        private final String outerSqlName;
        private final String labelDst;
        private final String labelRepl;
        private final String labelSrc;
        private final String labelRegex;
        private final String pipe;

        private Parsed(RexInputRef value,
                       String function,
                       String window,
                       List<String> args,
                       String agg,
                       List<String> innerScalars,
                       List<String> scalars,
                       String sqlName,
                       String whenCompare,
                       String whenStep,
                       String whenExtra,
                       String offset,
                       String rank,
                       String rankCount,
                       String outerFunction,
                       String outerWindow,
                       String outerStep,
                       String outerSqlName,
                       String labelDst,
                       String labelRepl,
                       String labelSrc,
                       String labelRegex,
                       String pipe) {
            this.value = value;
            this.function = function;
            this.window = window;
            this.args = args;
            this.agg = agg;
            this.innerScalars = innerScalars;
            this.scalars = scalars;
            this.sqlName = sqlName;
            this.whenCompare = whenCompare;
            this.whenStep = whenStep;
            this.whenExtra = whenExtra;
            this.offset = offset;
            this.rank = rank;
            this.rankCount = rankCount;
            this.outerFunction = outerFunction;
            this.outerWindow = outerWindow;
            this.outerStep = outerStep;
            this.outerSqlName = outerSqlName;
            this.labelDst = labelDst;
            this.labelRepl = labelRepl;
            this.labelSrc = labelSrc;
            this.labelRegex = labelRegex;
            this.pipe = pipe;
        }

        private boolean shaped() {
            return offset != null || rank != null || outerFunction != null || labelDst != null;
        }
    }

    private static final class Modifiers {
        private String offset;
        private String rank;
        private String rankCount;
        private String outerFunction;
        private String outerWindow;
        private String outerStep;
        private String outerSqlName;
        private String labelDst;
        private String labelRepl;
        private String labelSrc;
        private String labelRegex;

        private boolean shaped() {
            return offset != null || rank != null || outerFunction != null || labelDst != null;
        }
    }

    private static final class WhenCall {
        private final RexInputRef value;
        private final String promql;
        private final String window;
        private final String compare;
        private final String step;
        private final String extra;
        private final String sqlName;

        private WhenCall(RexInputRef value,
                         String promql,
                         String window,
                         String compare,
                         String step,
                         String extra,
                         String sqlName) {
            this.value = value;
            this.promql = promql;
            this.window = window;
            this.compare = compare;
            this.step = step;
            this.extra = extra;
            this.sqlName = sqlName;
        }
    }

    private static final class Comparison {
        private final RexInputRef value;
        private final String promql;

        private Comparison(RexInputRef value, String promql) {
            this.value = value;
            this.promql = promql;
        }
    }

    private static RexNode unwrapCast(RexNode node) {
        if (node instanceof RexCall call && call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
            return unwrapCast(call.getOperands().get(0));
        }
        return node;
    }

    private static RexInputRef valueRef(RexNode node) {
        node = unwrapCast(node);
        return node instanceof RexInputRef ref ? ref : null;
    }

    private static String positiveInt(RexNode node) {
        String number = numberOf(node);
        if (number == null) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(number);
            if (value.signum() <= 0
                    || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0
                    || value.stripTrailingZeros().scale() > 0) {
                return null;
            }
            return value.toBigInteger().toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOf(RexNode node) {
        node = unwrapCast(node);
        if (!(node instanceof RexLiteral literal) || literal.getTypeName().getFamily() != SqlTypeFamily.CHARACTER) {
            return null;
        }
        return RexLiteral.stringValue(literal);
    }

    private static String numberOf(RexNode node) {
        node = unwrapCast(node);
        if (!(node instanceof RexLiteral literal) || literal.getTypeName().getFamily() != SqlTypeFamily.NUMERIC) {
            return null;
        }
        BigDecimal value = literal.getValueAs(BigDecimal.class);
        return value == null ? null : value.stripTrailingZeros().toPlainString();
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

package org.lokrusta.sqlprometheus.rel;

import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.logical.LogicalFilter;
import org.apache.calcite.rel.logical.LogicalJoin;
import org.apache.calcite.rel.logical.LogicalProject;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexLiteral;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.type.SqlTypeName;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Error text built from the query that failed: the same calls, constants, and timestamp bounds.
 */
public final class PrometheusMessages {
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneOffset.UTC);

    private PrometheusMessages() {
    }

    public static String callText(String sqlName, String window, List<String> args) {
        StringBuilder text = new StringBuilder(sqlName == null ? "PROM" : sqlName).append("(\"value\"");
        if (window != null && !window.isEmpty()) {
            int colon = window.indexOf(':');
            if (colon >= 0) {
                text.append(", '").append(window, 0, colon).append("', '").append(window.substring(colon + 1)).append("'");
            } else {
                text.append(", '").append(window).append("'");
            }
        }
        if (args != null) {
            for (String arg : args) {
                if (arg != null && !arg.isEmpty()) {
                    text.append(", ").append(arg);
                }
            }
        }
        return text.append(')').toString();
    }

    public static String twoExpressions(PrometheusTableScan scan, String leftCall, String rightCall) {
        String metric = scan.getPrometheusTable().metric();
        String where = whereClause(scan);
        if (rightCall == null) {
            return metric + " cannot compute " + leftCall
                    + " together with another expression on the same metric.\n"
                    + "Read the metric in a separate query for each expression:\n"
                    + "SELECT " + leftCall + "\nFROM " + metric + where;
        }
        return metric + " cannot compute both " + leftCall + " and " + rightCall + " on one scan.\n"
                + "SELECT " + leftCall + "\nFROM " + metric + where + "\n\n"
                + "SELECT " + rightCall + "\nFROM " + metric + where;
    }

    public static String labelCondition(String callSql, PrometheusTableScan scan, RexNode condition, RelNode scope) {
        List<String> fields = scope.getRowType().getFieldNames();
        List<RexNode> deferred = new ArrayList<>();
        List<RexNode> rest = new ArrayList<>();
        for (RexNode conjunct : RelOptUtil.conjunctions(condition)) {
            if (isTimestamp(conjunct, fields) || isLabelCompare(conjunct, fields)) {
                deferred.add(conjunct);
            } else {
                rest.add(conjunct);
            }
        }
        RexNode remainder = rest.isEmpty()
                ? null
                : rest.size() == 1 ? rest.get(0) : compose(scope, SqlKind.AND, rest);
        String metric = scan.getPrometheusTable().metric();
        String printed = print(condition, fields);
        if (remainder != null) {
            List<List<RexNode>> terms = dnf(remainder);
            if (!terms.isEmpty() && terms.stream().allMatch(term -> term.stream().allMatch(node -> isLabelCompare(node, fields)))) {
                return callSql + " on " + metric
                        + " cannot push this label condition into a Prometheus selector.\n"
                        + printed + "\n"
                        + union(callSql, metric, fields, terms, deferred, scan);
            }
        }
        Set<String> labels = labelNames(condition, fields);
        return callSql + " on " + metric
                + " cannot push this label condition into a Prometheus selector.\n"
                + printed + "\n"
                + "A selector accepts label = string and label <> string combined with AND. "
                + "OR, LIKE and a comparison of two labels do not fit one selector. "
                + "To filter before the function, replace a pattern with the concrete equalities yourself and write one SELECT per equality, combined with UNION.\n"
                + "The function then runs on all series, and the condition filters an alias of the result:\n"
                + alias(callSql, metric, labels, scan, print(condition, fields));
    }

    public static String complexValue(String sqlName, String window, PrometheusTableScan scan, RelNode input) {
        String name = sqlName == null ? "PROM" : sqlName;
        String metric = scan.getPrometheusTable().metric();
        RexNode condition = valueCondition(input, scan);
        List<String> fields = condition == null ? List.of() : fieldsOf(input, scan);
        String printed = condition == null ? "\"value\"" : print(condition, fields);
        String comparison = condition == null ? "\"value\" > 1" : firstValueComparison(condition, fields);
        if (comparison == null) {
            comparison = "\"value\" > 1";
        }
        String whenWindow = window == null || window.isEmpty() ? "5m" : window;
        int colon = whenWindow.indexOf(':');
        if (colon >= 0) {
            whenWindow = whenWindow.substring(0, colon);
        }
        String aliasWhere = printed.replace("\"value\"", "v");
        return name + " cannot apply this \"value\" condition before the function.\n"
                + printed + "\n"
                + "One comparison belongs in " + name + "_WHEN, for example:\n"
                + "  SELECT " + name + "_WHEN(" + comparison + ", '" + whenWindow + "') FROM " + metric
                + whereClause(scan) + "\n"
                + "The function then runs on all series. Filter an alias of the result:\n"
                + "  SELECT v FROM (SELECT " + callText(name, whenWindow, List.of()) + " AS v FROM " + metric
                + whereClause(scan) + ") t WHERE " + aliasWhere;
    }

    public static String foldAggregate(String sqlAggregate, String promAggregate, PrometheusTableScan scan) {
        String metric = scan.getPrometheusTable().metric();
        String promName = promSqlName(promAggregate);
        String where = whereClause(scan);
        return sqlAggregate + " cannot fold the Prometheus aggregate " + promName + " on " + metric + ".\n"
                + sqlAggregate + "(" + promName + "(\"value\")) aggregates series that Prometheus already folded.\n"
                + "SELECT " + sqlAggregate + "(PROM_RATE(\"value\", '5m')) FROM " + metric + where + "\n"
                + "SELECT " + promName + "(\"value\") FROM " + metric + where;
    }

    public static String whereClause(PrometheusTableScan scan) {
        List<String> parts = whereParts(scan);
        if (parts.isEmpty()) {
            return "";
        }
        return "\nWHERE " + String.join("\n  AND ", parts);
    }

    private static String union(String callSql,
                                String metric,
                                List<String> fields,
                                List<List<RexNode>> terms,
                                List<RexNode> deferred,
                                PrometheusTableScan scan) {
        Set<String> labels = new LinkedHashSet<>();
        for (List<RexNode> term : terms) {
            for (RexNode factor : term) {
                labels.addAll(labelNames(factor, fields));
            }
        }
        for (RexNode node : deferred) {
            labels.addAll(labelNames(node, fields));
        }
        for (String label : scan.getLabelFilters().keySet()) {
            labels.add(label);
        }
        String selectLabels = labels.isEmpty() ? "" : ", " + String.join(", ", labels);
        StringBuilder text = new StringBuilder();
        boolean first = true;
        for (List<RexNode> term : terms) {
            if (!first) {
                text.append("\nUNION\n");
            }
            first = false;
            List<String> parts = new ArrayList<>();
            Set<String> used = new LinkedHashSet<>();
            for (RexNode factor : term) {
                parts.add(print(factor, fields));
                used.addAll(labelNames(factor, fields));
            }
            for (RexNode node : deferred) {
                parts.add(print(node, fields));
                used.addAll(labelNames(node, fields));
            }
            boolean timestamp = deferred.stream().anyMatch(node -> isTimestamp(node, fields));
            for (String part : whereParts(scan)) {
                if (part.startsWith("\"timestamp\"") && timestamp) {
                    continue;
                }
                String label = part.contains(" ") ? part.substring(0, part.indexOf(' ')) : part;
                if (used.contains(label)) {
                    continue;
                }
                parts.add(part);
            }
            text.append("SELECT ").append(callSql).append(" AS v").append(selectLabels)
                    .append("\nFROM ").append(metric)
                    .append("\nWHERE ").append(String.join("\n  AND ", parts));
        }
        return text.toString();
    }

    private static String alias(String callSql,
                                String metric,
                                Set<String> labels,
                                PrometheusTableScan scan,
                                String condition) {
        String extra = labels.isEmpty() ? "" : ", " + String.join(", ", labels);
        return "  SELECT * FROM (SELECT " + callSql + " AS v" + extra + " FROM " + metric
                + whereClause(scan) + ") t WHERE " + condition;
    }

    private static List<String> whereParts(PrometheusTableScan scan) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> filter : scan.getLabelFilters().entrySet()) {
            String stored = filter.getValue();
            if (stored.startsWith("!=")) {
                parts.add(filter.getKey() + " <> '" + stored.substring(2).replace("'", "''") + "'");
            } else {
                parts.add(filter.getKey() + " = '" + stored.replace("'", "''") + "'");
            }
        }
        if (scan.getStartMillis() != null) {
            parts.add("\"timestamp\" >= TIMESTAMP '" + TIMESTAMP.format(Instant.ofEpochMilli(scan.getStartMillis())) + "'");
        }
        if (scan.getEndMillis() != null) {
            parts.add("\"timestamp\" <= TIMESTAMP '" + TIMESTAMP.format(Instant.ofEpochMilli(scan.getEndMillis())) + "'");
        }
        return parts;
    }

    private static List<List<RexNode>> dnf(RexNode node) {
        List<RexNode> or = RelOptUtil.disjunctions(node);
        if (or.size() > 1) {
            List<List<RexNode>> terms = new ArrayList<>();
            for (RexNode part : or) {
                terms.addAll(dnf(part));
            }
            return terms;
        }
        List<RexNode> and = RelOptUtil.conjunctions(node);
        if (and.size() > 1) {
            List<List<RexNode>> product = new ArrayList<>();
            product.add(new ArrayList<>());
            for (RexNode part : and) {
                List<List<RexNode>> next = new ArrayList<>();
                for (List<RexNode> left : product) {
                    for (List<RexNode> right : dnf(part)) {
                        List<RexNode> merged = new ArrayList<>(left);
                        merged.addAll(right);
                        next.add(merged);
                    }
                }
                product = next;
            }
            return product;
        }
        return List.of(List.of(node));
    }

    private static boolean isLabelCompare(RexNode node, List<String> fields) {
        RexCall call = comparison(node);
        if (call == null || (call.getKind() != SqlKind.EQUALS && call.getKind() != SqlKind.NOT_EQUALS)) {
            return false;
        }
        RexInputRef ref = inputRef(call.getOperands().get(0));
        RexLiteral literal = charLiteral(call.getOperands().get(1));
        if (ref == null || literal == null) {
            ref = inputRef(call.getOperands().get(1));
            literal = charLiteral(call.getOperands().get(0));
        }
        return ref != null && literal != null && isLabel(ref.getIndex(), fields);
    }

    private static boolean isTimestamp(RexNode node, List<String> fields) {
        RexCall call = comparison(node);
        if (call == null) {
            return false;
        }
        RexInputRef left = inputRef(call.getOperands().get(0));
        RexInputRef right = inputRef(call.getOperands().get(1));
        if (left != null && right != null) {
            return false;
        }
        RexInputRef ref = left != null ? left : right;
        return ref != null && isTimestamp(ref.getIndex(), fields);
    }

    private static boolean isLabel(int index, List<String> fields) {
        if (index < 0 || index >= fields.size()) {
            return false;
        }
        String name = fields.get(index);
        return !"timestamp".equalsIgnoreCase(name) && !"value".equalsIgnoreCase(name);
    }

    private static boolean isTimestamp(int index, List<String> fields) {
        return index >= 0 && index < fields.size() && "timestamp".equalsIgnoreCase(fields.get(index));
    }

    private static Set<String> labelNames(RexNode node, List<String> fields) {
        Set<String> names = new LinkedHashSet<>();
        collectLabels(node, fields, names);
        return names;
    }

    private static void collectLabels(RexNode node, List<String> fields, Set<String> names) {
        RexInputRef ref = inputRef(node);
        if (ref != null) {
            if (isLabel(ref.getIndex(), fields)) {
                names.add(fields.get(ref.getIndex()));
            }
            return;
        }
        if (node instanceof RexCall call) {
            for (RexNode operand : call.getOperands()) {
                collectLabels(operand, fields, names);
            }
        }
    }

    private static String print(RexNode node, List<String> fields) {
        RexInputRef ref = inputRef(node);
        if (ref != null) {
            if (ref.getIndex() < 0 || ref.getIndex() >= fields.size()) {
                return "$" + ref.getIndex();
            }
            String name = fields.get(ref.getIndex());
            if ("timestamp".equalsIgnoreCase(name) || "value".equalsIgnoreCase(name)) {
                return "\"" + name.toLowerCase(Locale.ROOT) + "\"";
            }
            return name;
        }
        RexLiteral literal = literal(node);
        if (literal != null) {
            if (literal.getTypeName() == SqlTypeName.CHAR || literal.getTypeName() == SqlTypeName.VARCHAR) {
                return "'" + RexLiteral.stringValue(literal).replace("'", "''") + "'";
            }
            return literal.getValueAs(String.class);
        }
        if (node instanceof RexCall call) {
            if (call.getKind() == SqlKind.CAST && !call.getOperands().isEmpty()) {
                return print(call.getOperands().get(0), fields);
            }
            if (call.getOperands().size() == 2 && infix(call.getKind()) != null) {
                return print(call.getOperands().get(0), fields) + " " + infix(call.getKind()) + " "
                        + print(call.getOperands().get(1), fields);
            }
            if (call.getKind() == SqlKind.AND || call.getKind() == SqlKind.OR) {
                String joiner = call.getKind() == SqlKind.AND ? " AND " : " OR ";
                List<String> parts = new ArrayList<>();
                for (RexNode operand : call.getOperands()) {
                    parts.add(print(operand, fields));
                }
                return String.join(joiner, parts);
            }
            if (call.getKind() == SqlKind.LIKE && call.getOperands().size() >= 2) {
                return print(call.getOperands().get(0), fields) + " LIKE " + print(call.getOperands().get(1), fields);
            }
            return call.getOperator().getName() + "(" + printList(call.getOperands(), fields) + ")";
        }
        return String.valueOf(node);
    }

    private static String printList(List<RexNode> nodes, List<String> fields) {
        List<String> parts = new ArrayList<>();
        for (RexNode node : nodes) {
            parts.add(print(node, fields));
        }
        return String.join(", ", parts);
    }

    private static String infix(SqlKind kind) {
        return switch (kind) {
            case EQUALS -> "=";
            case NOT_EQUALS -> "<>";
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            default -> null;
        };
    }

    private static String firstValueComparison(RexNode node, List<String> fields) {
        if (node instanceof RexCall call && infix(call.getKind()) != null && call.getOperands().size() == 2) {
            RexInputRef ref = inputRef(call.getOperands().get(0));
            if (ref == null) {
                ref = inputRef(call.getOperands().get(1));
            }
            if (ref != null && ref.getIndex() < fields.size() && "value".equalsIgnoreCase(fields.get(ref.getIndex()))) {
                return print(call, fields);
            }
        }
        if (node instanceof RexCall call) {
            for (RexNode operand : call.getOperands()) {
                String found = firstValueComparison(operand, fields);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static RexNode valueCondition(RelNode node, PrometheusTableScan scan) {
        if (node instanceof LogicalFilter filter) {
            if (reaches(filter.getInput(), scan) && mentionsValue(filter.getCondition(), filter.getInput())) {
                return filter.getCondition();
            }
            return valueCondition(filter.getInput(), scan);
        }
        if (node instanceof LogicalProject project) {
            return valueCondition(project.getInput(), scan);
        }
        if (node instanceof LogicalJoin join) {
            RexNode left = valueCondition(join.getLeft(), scan);
            return left != null ? left : valueCondition(join.getRight(), scan);
        }
        return null;
    }

    private static boolean mentionsValue(RexNode node, RelNode scope) {
        List<String> fields = scope.getRowType().getFieldNames();
        return firstValueComparison(node, fields) != null || print(node, fields).contains("\"value\"");
    }

    private static List<String> fieldsOf(RelNode node, PrometheusTableScan scan) {
        RexNode ignored = valueCondition(node, scan);
        if (node instanceof LogicalFilter filter && filter.getCondition() == ignored) {
            return filter.getInput().getRowType().getFieldNames();
        }
        if (node instanceof LogicalFilter filter) {
            return fieldsOf(filter.getInput(), scan);
        }
        if (node instanceof LogicalProject project) {
            return fieldsOf(project.getInput(), scan);
        }
        if (node instanceof LogicalJoin join) {
            if (reaches(join.getLeft(), scan)) {
                return fieldsOf(join.getLeft(), scan);
            }
            return fieldsOf(join.getRight(), scan);
        }
        return node.getRowType().getFieldNames();
    }

    private static boolean reaches(RelNode node, PrometheusTableScan scan) {
        if (node == scan) {
            return true;
        }
        if (node instanceof LogicalFilter filter) {
            return reaches(filter.getInput(), scan);
        }
        if (node instanceof LogicalProject project) {
            return reaches(project.getInput(), scan);
        }
        if (node instanceof LogicalJoin join) {
            return reaches(join.getLeft(), scan) || reaches(join.getRight(), scan);
        }
        return false;
    }

    private static RexNode compose(RelNode scope, SqlKind kind, List<RexNode> nodes) {
        return kind == SqlKind.AND
                ? org.apache.calcite.rex.RexUtil.composeConjunction(scope.getCluster().getRexBuilder(), nodes)
                : org.apache.calcite.rex.RexUtil.composeDisjunction(scope.getCluster().getRexBuilder(), nodes);
    }

    private static RexCall comparison(RexNode node) {
        if (node instanceof RexCall call && infix(call.getKind()) != null && call.getOperands().size() == 2) {
            return call;
        }
        return null;
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

    private static RexLiteral charLiteral(RexNode node) {
        RexLiteral literal = literal(node);
        if (literal == null) {
            return null;
        }
        SqlTypeName type = literal.getTypeName();
        return type == SqlTypeName.CHAR || type == SqlTypeName.VARCHAR ? literal : null;
    }

    private static String promSqlName(String promql) {
        if (promql == null) {
            return "PROM_SUM";
        }
        return switch (promql) {
            case "sum" -> "PROM_SUM";
            case "min" -> "PROM_MIN";
            case "max" -> "PROM_MAX";
            case "avg" -> "PROM_AVG";
            case "count" -> "PROM_COUNT";
            default -> promql.startsWith("PROM_") ? promql : "PROM_" + promql.toUpperCase(Locale.ROOT);
        };
    }
}

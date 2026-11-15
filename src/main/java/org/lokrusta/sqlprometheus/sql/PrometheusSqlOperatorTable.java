package org.lokrusta.sqlprometheus.sql;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Стандартные операторы Calcite плюс PROM_*.
 * JDBC не умеет подставить этот класс через {@code fun=}: там только библиотеки
 * {@code standard,mysql}. Для соединения те же имена отдаёт схема как функции,
 * которые правило обязано убрать до генерации кода.
 */
public final class PrometheusSqlOperatorTable extends ChainedSqlOperatorTable {
    private static final Pattern WINDOW = Pattern.compile("(?:[0-9]+(?:ms|s|m|h|d|w|y))+");
    private static final Pattern DURATION_PART = Pattern.compile("(ms|s|m|h|d|w|y)");
    private static final Map<String, String> RANGE = Map.ofEntries(
            Map.entry("PROM_RATE", "rate"),
            Map.entry("PROM_IRATE", "irate"),
            Map.entry("PROM_INCREASE", "increase"),
            Map.entry("PROM_DELTA", "delta"),
            Map.entry("PROM_IDELTA", "idelta"),
            Map.entry("PROM_CHANGES", "changes"),
            Map.entry("PROM_DERIV", "deriv"),
            Map.entry("PROM_RESET", "resets"),
            Map.entry("PROM_MAD", "mad_over_time"),
            Map.entry("PROM_PREDICT_LINEAR", "predict_linear"),
            Map.entry("PROM_HOLT_WINTERS", "holt_winters"),
            Map.entry("PROM_AVG_OVER_TIME", "avg_over_time"),
            Map.entry("PROM_MIN_OVER_TIME", "min_over_time"),
            Map.entry("PROM_MAX_OVER_TIME", "max_over_time"),
            Map.entry("PROM_SUM_OVER_TIME", "sum_over_time"),
            Map.entry("PROM_COUNT_OVER_TIME", "count_over_time"),
            Map.entry("PROM_STDDEV_OVER_TIME", "stddev_over_time"),
            Map.entry("PROM_STDVAR_OVER_TIME", "stdvar_over_time"),
            Map.entry("PROM_LAST_OVER_TIME", "last_over_time"),
            Map.entry("PROM_PRESENT_OVER_TIME", "present_over_time"),
            Map.entry("PROM_QUANTILE_OVER_TIME", "quantile_over_time"));
    private static final Map<String, String> AGGREGATE = Map.of(
            "PROM_SUM", "sum",
            "PROM_AVG", "avg",
            "PROM_MIN", "min",
            "PROM_MAX", "max",
            "PROM_COUNT", "count");
    private static final List<SqlFunction> FUNCTIONS = functions();

    public static final PrometheusSqlOperatorTable INSTANCE = new PrometheusSqlOperatorTable();

    private PrometheusSqlOperatorTable() {
        super(List.of(
                SqlLibraryOperatorTableFactory.INSTANCE.getOperatorTable(SqlLibrary.STANDARD, SqlLibrary.MYSQL),
                SqlOperatorTables.of(FUNCTIONS)));
    }

    public static List<String> functionNames() {
        return FUNCTIONS.stream().map(SqlFunction::getName).toList();
    }

    public static String promqlName(String sqlName) {
        if (sqlName == null) {
            return null;
        }
        return RANGE.get(sqlName.toUpperCase(Locale.ROOT));
    }

    public static String promqlAggregate(String sqlName) {
        if (sqlName == null) {
            return null;
        }
        return AGGREGATE.get(sqlName.toUpperCase(Locale.ROOT));
    }

    public static boolean validWindow(String window) {
        return window != null && WINDOW.matcher(window).matches();
    }

    public static long durationMillis(String text) {
        if (text == null || !validWindow(text)) {
            return -1;
        }
        Matcher matcher = DURATION_PART.matcher(text);
        long total = 0;
        int digitsStart = 0;
        while (matcher.find()) {
            if (matcher.start() == digitsStart) {
                return -1;
            }
            long amount = Long.parseLong(text.substring(digitsStart, matcher.start()));
            if (amount < 0) {
                return -1;
            }
            long unit = switch (matcher.group()) {
                case "ms" -> 1L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                case "d" -> 86_400_000L;
                case "w" -> 604_800_000L;
                case "y" -> 31_536_000_000L;
                default -> -1L;
            };
            if (unit < 0 || amount > Long.MAX_VALUE / unit) {
                return -1;
            }
            total += amount * unit;
            digitsStart = matcher.end();
        }
        return digitsStart == text.length() && total > 0 ? total : -1;
    }

    private static List<SqlFunction> functions() {
        List<SqlFunction> functions = new ArrayList<>();
        for (String name : RANGE.keySet().stream().sorted().toList()) {
            functions.add(switch (name) {
                case "PROM_PREDICT_LINEAR" -> predictLinear();
                case "PROM_HOLT_WINTERS" -> holtWinters();
                case "PROM_QUANTILE_OVER_TIME" -> quantileOverTime();
                default -> name.endsWith("_OVER_TIME") ? overTime(name) : windowFunction(name);
            });
            functions.add(whenFunction(name));
        }
        for (String name : AGGREGATE.keySet().stream().sorted().toList()) {
            functions.add(aggregate(name));
        }
        functions.add(offset());
        functions.add(rank("PROM_TOPK"));
        functions.add(rank("PROM_BOTTOMK"));
        functions.add(labelReplace());
        return List.copyOf(functions);
    }

    private static SqlFunction windowFunction(String name) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                WindowOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction predictLinear() {
        return new SqlFunction(
                "PROM_PREDICT_LINEAR",
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                PredictOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction holtWinters() {
        return new SqlFunction(
                "PROM_HOLT_WINTERS",
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                HoltOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction overTime(String name) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                OverTimeOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction quantileOverTime() {
        return new SqlFunction(
                "PROM_QUANTILE_OVER_TIME",
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                QuantileOverTimeOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction offset() {
        return new SqlFunction(
                "PROM_OFFSET",
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                OffsetOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction rank(String name) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                RankOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction labelReplace() {
        return new SqlFunction(
                "PROM_LABEL_REPLACE",
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                LabelReplaceOperandChecker.INSTANCE,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction whenFunction(String name) {
        return switch (name) {
            case "PROM_PREDICT_LINEAR" -> whenCall("PROM_PREDICT_LINEAR_WHEN", 1, false);
            case "PROM_HOLT_WINTERS" -> whenCall("PROM_HOLT_WINTERS_WHEN", 2, true);
            case "PROM_QUANTILE_OVER_TIME" -> whenCall("PROM_QUANTILE_OVER_TIME_WHEN", 1, true);
            default -> whenCall(name + "_WHEN", 0, false);
        };
    }

    private static SqlFunction whenCall(String name, int extras, boolean unitFactors) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                new WhenOperandChecker(extras, unitFactors),
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static SqlFunction aggregate(String name) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                OperandTypes.NUMERIC,
                SqlFunctionCategory.USER_DEFINED_FUNCTION);
    }

    private static final class WindowOperandChecker implements SqlOperandTypeChecker {
        private static final WindowOperandChecker INSTANCE = new WindowOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            if (!OperandTypes.NUMERIC_CHARACTER.checkOperandTypes(callBinding, throwOnFailure)) {
                return false;
            }
            if (!callBinding.isOperandLiteral(1, false) || !validWindow(callBinding.getStringLiteralOperand(1))) {
                if (throwOnFailure) {
                    throw callBinding.newValidationSignatureError();
                }
                return false;
            }
            return true;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(2);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal>)";
        }
    }

    private static final class PredictOperandChecker implements SqlOperandTypeChecker {
        private static final PredictOperandChecker INSTANCE = new PredictOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            if (!SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0))
                    || !SqlTypeFamily.CHARACTER.contains(callBinding.getOperandType(1))
                    || !SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(2))) {
                if (throwOnFailure) {
                    throw callBinding.newValidationSignatureError();
                }
                return false;
            }
            if (!callBinding.isOperandLiteral(1, false)
                    || !validWindow(callBinding.getStringLiteralOperand(1))
                    || !callBinding.isOperandLiteral(2, false)) {
                if (throwOnFailure) {
                    throw callBinding.newValidationSignatureError();
                }
                return false;
            }
            return true;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(3);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal>, <numeric literal>)";
        }
    }

    private static final class HoltOperandChecker implements SqlOperandTypeChecker {
        private static final HoltOperandChecker INSTANCE = new HoltOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            if (!SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0))
                    || !SqlTypeFamily.CHARACTER.contains(callBinding.getOperandType(1))
                    || !SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(2))
                    || !SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(3))) {
                if (throwOnFailure) {
                    throw callBinding.newValidationSignatureError();
                }
                return false;
            }
            BigDecimal sf = callBinding.isOperandLiteral(2, false)
                    ? callBinding.getOperandLiteralValue(2, BigDecimal.class) : null;
            BigDecimal tf = callBinding.isOperandLiteral(3, false)
                    ? callBinding.getOperandLiteralValue(3, BigDecimal.class) : null;
            if (!callBinding.isOperandLiteral(1, false)
                    || !validWindow(callBinding.getStringLiteralOperand(1))
                    || !unitInterval(sf)
                    || !unitInterval(tf)) {
                if (throwOnFailure) {
                    throw callBinding.newValidationSignatureError();
                }
                return false;
            }
            return true;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(4);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal>, <factor>, <factor>)";
        }
    }

    private static final class OverTimeOperandChecker implements SqlOperandTypeChecker {
        private static final OverTimeOperandChecker INSTANCE = new OverTimeOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            int count = callBinding.getOperandCount();
            boolean ok = (count == 2 || count == 3)
                    && SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0))
                    && durationLiteral(callBinding, 1)
                    && (count == 2 || durationLiteral(callBinding, 2));
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.between(2, 3);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal> [, <step>])";
        }
    }

    private static final class QuantileOverTimeOperandChecker implements SqlOperandTypeChecker {
        private static final QuantileOverTimeOperandChecker INSTANCE = new QuantileOverTimeOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            BigDecimal phi = callBinding.isOperandLiteral(2, false)
                    ? callBinding.getOperandLiteralValue(2, BigDecimal.class) : null;
            boolean ok = SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0))
                    && durationLiteral(callBinding, 1)
                    && unitInterval(phi);
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(3);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal>, <phi>)";
        }
    }

    private static final class OffsetOperandChecker implements SqlOperandTypeChecker {
        private static final OffsetOperandChecker INSTANCE = new OffsetOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            boolean ok = SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0))
                    && durationLiteral(callBinding, 1);
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(2);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <duration literal>)";
        }
    }

    private static final class RankOperandChecker implements SqlOperandTypeChecker {
        private static final RankOperandChecker INSTANCE = new RankOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            BigDecimal k = callBinding.isOperandLiteral(0, false)
                    ? callBinding.getOperandLiteralValue(0, BigDecimal.class) : null;
            boolean ok = positiveInt(k) && SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(1));
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(2);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<positive integer>, <numeric>)";
        }
    }

    private static final class LabelReplaceOperandChecker implements SqlOperandTypeChecker {
        private static final LabelReplaceOperandChecker INSTANCE = new LabelReplaceOperandChecker();

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            boolean ok = SqlTypeFamily.NUMERIC.contains(callBinding.getOperandType(0));
            for (int i = 1; ok && i < 5; i++) {
                ok = callBinding.isOperandLiteral(i, false)
                        && SqlTypeFamily.CHARACTER.contains(callBinding.getOperandType(i));
            }
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.of(5);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            return opName + "(<numeric>, <destination>, <replacement>, <source>, <regex>)";
        }
    }

    private static final class WhenOperandChecker implements SqlOperandTypeChecker {
        private final int extras;
        private final boolean unitFactors;

        private WhenOperandChecker(int extras, boolean unitFactors) {
            this.extras = extras;
            this.unitFactors = unitFactors;
        }

        @Override
        public boolean checkOperandTypes(SqlCallBinding callBinding, boolean throwOnFailure) {
            int count = callBinding.getOperandCount();
            boolean ok = count >= 2 + extras
                    && count <= 3 + extras
                    && valueComparison(callBinding.operand(0))
                    && durationLiteral(callBinding, 1);
            for (int i = 0; ok && i < extras; i++) {
                BigDecimal number = callBinding.isOperandLiteral(2 + i, false)
                        ? callBinding.getOperandLiteralValue(2 + i, BigDecimal.class) : null;
                ok = number != null && (!unitFactors || unitInterval(number));
            }
            if (ok && count == 3 + extras) {
                ok = durationLiteral(callBinding, 2 + extras);
            }
            if (!ok && throwOnFailure) {
                throw callBinding.newValidationSignatureError();
            }
            return ok;
        }

        @Override
        public SqlOperandCountRange getOperandCountRange() {
            return SqlOperandCountRanges.between(2 + extras, 3 + extras);
        }

        @Override
        public String getAllowedSignatures(SqlOperator op, String opName) {
            String tail = switch (extras) {
                case 1 -> ", <numeric literal>";
                case 2 -> ", <factor>, <factor>";
                default -> "";
            };
            return opName + "(<value comparison>, <duration literal>" + tail + " [, <step>])";
        }
    }

    private static boolean valueComparison(SqlNode node) {
        if (!(node instanceof SqlCall call) || call.operandCount() != 2) {
            return false;
        }
        SqlKind kind = call.getKind();
        if (kind != SqlKind.GREATER_THAN
                && kind != SqlKind.GREATER_THAN_OR_EQUAL
                && kind != SqlKind.LESS_THAN
                && kind != SqlKind.LESS_THAN_OR_EQUAL
                && kind != SqlKind.EQUALS) {
            return false;
        }
        return numericLiteral(call.operand(0)) ^ numericLiteral(call.operand(1));
    }

    private static boolean numericLiteral(SqlNode node) {
        if (node.getKind() == SqlKind.CAST && node instanceof SqlCall call && call.operandCount() >= 1) {
            return numericLiteral(call.operand(0));
        }
        return node instanceof SqlLiteral literal && literal.getTypeName().getFamily() == SqlTypeFamily.NUMERIC;
    }

    private static boolean durationLiteral(SqlCallBinding callBinding, int index) {
        return callBinding.isOperandLiteral(index, false)
                && validWindow(callBinding.getStringLiteralOperand(index));
    }

    private static boolean unitInterval(BigDecimal value) {
        return value != null
                && value.compareTo(BigDecimal.ZERO) >= 0
                && value.compareTo(BigDecimal.ONE) <= 0;
    }

    private static boolean positiveInt(BigDecimal value) {
        if (value == null || value.signum() <= 0 || value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
            return false;
        }
        return value.stripTrailingZeros().scale() <= 0;
    }
}

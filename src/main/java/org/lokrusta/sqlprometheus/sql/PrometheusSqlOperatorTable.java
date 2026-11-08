package org.lokrusta.sqlprometheus.sql;

import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
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
import java.util.regex.Pattern;

/**
 * Стандартные операторы Calcite плюс PROM_*.
 * JDBC не умеет подставить этот класс через {@code fun=}: там только библиотеки
 * {@code standard,mysql}. Для соединения те же имена отдаёт схема как функции,
 * которые правило обязано убрать до генерации кода.
 */
public final class PrometheusSqlOperatorTable extends ChainedSqlOperatorTable {
    private static final Pattern WINDOW = Pattern.compile("(?:[0-9]+(?:ms|s|m|h|d|w|y))+");
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
            Map.entry("PROM_HOLT_WINTERS", "holt_winters"));
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

    private static List<SqlFunction> functions() {
        List<SqlFunction> functions = new ArrayList<>();
        for (String name : RANGE.keySet().stream().sorted().toList()) {
            functions.add(switch (name) {
                case "PROM_PREDICT_LINEAR" -> predictLinear();
                case "PROM_HOLT_WINTERS" -> holtWinters();
                default -> windowFunction(name);
            });
        }
        for (String name : AGGREGATE.keySet().stream().sorted().toList()) {
            functions.add(aggregate(name));
        }
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

    private static boolean unitInterval(BigDecimal value) {
        return value != null
                && value.compareTo(BigDecimal.ZERO) >= 0
                && value.compareTo(BigDecimal.ONE) <= 0;
    }
}

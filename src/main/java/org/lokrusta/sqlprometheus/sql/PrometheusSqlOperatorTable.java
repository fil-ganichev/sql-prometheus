package org.lokrusta.sqlprometheus.sql;

import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlOperandCountRange;
import org.apache.calcite.sql.fun.SqlLibrary;
import org.apache.calcite.sql.fun.SqlLibraryOperatorTableFactory;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlOperandTypeChecker;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.util.SqlOperatorTables;
import org.apache.calcite.sql.SqlCallBinding;
import org.apache.calcite.sql.SqlOperator;

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
    private static final Map<String, String> PROMQL = Map.of(
            "PROM_RATE", "rate",
            "PROM_IRATE", "irate",
            "PROM_INCREASE", "increase",
            "PROM_DELTA", "delta",
            "PROM_IDELTA", "idelta",
            "PROM_CHANGES", "changes");
    private static final List<SqlFunction> FUNCTIONS = PROMQL.keySet().stream()
            .sorted()
            .map(PrometheusSqlOperatorTable::function)
            .toList();

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
        return PROMQL.get(sqlName.toUpperCase(Locale.ROOT));
    }

    public static boolean validWindow(String window) {
        return window != null && WINDOW.matcher(window).matches();
    }

    private static SqlFunction function(String name) {
        return new SqlFunction(
                name,
                SqlKind.OTHER_FUNCTION,
                ReturnTypes.DOUBLE,
                null,
                WindowOperandChecker.INSTANCE,
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
}

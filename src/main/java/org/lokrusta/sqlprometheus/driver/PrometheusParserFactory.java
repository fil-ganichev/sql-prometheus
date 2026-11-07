package org.lokrusta.sqlprometheus.driver;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlSnapshot;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWithItem;
import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;
import org.apache.calcite.util.Util;
import org.lokrusta.sqlprometheus.schema.PrometheusSchema;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PrometheusParserFactory implements SqlParserImplFactory {
    public static final PrometheusParserFactory INSTANCE = new PrometheusParserFactory();

    public PrometheusParserFactory() {
    }

    @Override
    public SqlAbstractParserImpl getParser(Reader stream) {
        return new PrometheusSqlParser(quoteValueColumn(stream));
    }

    private static final Pattern RESERVED_COLUMN = Pattern.compile("(?i)(\\.)(\\s*)(value|timestamp)\\b");

    /**
     * Calcite reserves {@code VALUE} and {@code TIMESTAMP}, so {@code c.value} is not an identifier.
     * The columns keep those names; a qualified reference is quoted before parsing.
     */
    private static Reader quoteValueColumn(Reader stream) {
        StringBuilder sql = new StringBuilder();
        char[] buffer = new char[4096];
        try {
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                sql.append(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String source = sql.toString().replaceAll("(?i)\\bNOW\\s*\\(\\s*\\)", "CURRENT_TIMESTAMP");
        Matcher matcher = RESERVED_COLUMN.matcher(source);
        StringBuilder quoted = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            quoted.append(source, last, matcher.start());
            quoted.append(matcher.group(1))
                    .append(matcher.group(2))
                    .append('"')
                    .append(matcher.group(3).toLowerCase(Locale.ROOT))
                    .append('"');
            last = matcher.end();
        }
        quoted.append(source, last, source.length());
        return new StringReader(quoted.toString());
    }

    public static final class PrometheusSqlParser extends SqlParserImpl {
        public PrometheusSqlParser(Reader reader) {
            super(reader);
        }

        @Override
        public SqlNode parseSqlStmtEof() throws Exception {
            SqlNode statement = super.parseSqlStmtEof();
            Set<String> tables = new LinkedHashSet<>();
            collect(statement, tables);
            PrometheusSchema.noteTables(tables);
            return statement;
        }

        @Override
        public void setConformance(SqlConformance conformance) {
            super.setConformance(new IdentifierConformance(conformance));
        }
    }

    private static final class IdentifierConformance extends SqlDelegatingConformance {
        private IdentifierConformance(SqlConformance delegate) {
            super(delegate);
        }

        @Override
        public boolean isLiberal() {
            return true;
        }

        @Override
        public boolean isValueAllowed() {
            return false;
        }
    }

    private static void collect(SqlNode node, Set<String> tables) {
        if (node instanceof SqlSelect select) {
            collectFrom(select.getFrom(), tables);
        } else if (node instanceof SqlOrderBy orderBy) {
            collect(orderBy.query, tables);
        } else if (node instanceof SqlWith with) {
            for (SqlNode item : with.withList) {
                collect(((SqlWithItem) item).query, tables);
            }
            collect(with.body, tables);
        } else if (node instanceof SqlCall call && setOperator(call.getOperator())) {
            for (SqlNode operand : call.getOperandList()) {
                collect(operand, tables);
            }
        }
    }

    private static void collectFrom(SqlNode from, Set<String> tables) {
        if (from instanceof SqlIdentifier identifier) {
            tables.add(Util.last(identifier.names));
        } else if (from instanceof SqlJoin join) {
            collectFrom(join.getLeft(), tables);
            collectFrom(join.getRight(), tables);
        } else if (from instanceof SqlSnapshot snapshot) {
            collectFrom(snapshot.getTableRef(), tables);
        } else if (from instanceof SqlSelect || from instanceof SqlOrderBy || from instanceof SqlWith) {
            collect(from, tables);
        } else if (from instanceof SqlCall call) {
            if (call.getOperandList().isEmpty()) {
                return;
            }
            collectFrom(call.operand(0), tables);
            if (setOperator(call.getOperator())) {
                for (int i = 1; i < call.operandCount(); i++) {
                    collectFrom(call.operand(i), tables);
                }
            }
        }
    }

    private static boolean setOperator(SqlOperator operator) {
        String name = operator.getName();
        return "UNION".equals(name) || "INTERSECT".equals(name) || "EXCEPT".equals(name);
    }
}

package org.lokrusta.sqlprometheus.driver;

import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.Locale;
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
}

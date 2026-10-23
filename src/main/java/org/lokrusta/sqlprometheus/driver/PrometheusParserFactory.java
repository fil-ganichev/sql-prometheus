package org.lokrusta.sqlprometheus.driver;

import org.apache.calcite.sql.parser.SqlAbstractParserImpl;
import org.apache.calcite.sql.parser.SqlParserImplFactory;
import org.apache.calcite.sql.parser.impl.SqlParserImpl;
import org.apache.calcite.sql.validate.SqlConformance;
import org.apache.calcite.sql.validate.SqlDelegatingConformance;

import java.io.Reader;

public final class PrometheusParserFactory implements SqlParserImplFactory {
    public static final PrometheusParserFactory INSTANCE = new PrometheusParserFactory();

    public PrometheusParserFactory() {
    }

    @Override
    public SqlAbstractParserImpl getParser(Reader stream) {
        return new PrometheusSqlParser(stream);
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

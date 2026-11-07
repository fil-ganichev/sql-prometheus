package org.lokrusta.sqlprometheus.driver;

import org.apache.calcite.runtime.Hook;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.tools.Program;
import org.apache.calcite.util.Holder;
import org.lokrusta.sqlprometheus.exec.PrometheusOptions;
import org.lokrusta.sqlprometheus.plan.PrometheusPrograms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

public class PrometheusDriver extends org.apache.calcite.jdbc.Driver {
    public static final String CONNECT_STRING_PREFIX = "jdbc:prometheus:";

    static {
        Consumer<Holder<Program>> program = holder -> holder.set(PrometheusPrograms.create());
        Hook.PROGRAM.add(program);
        // Calcite 1.38 rebuilds PERCENTILE_CONT while trimming unused fields and crashes
        // in SqlOperatorBinding.getCollationType. The scan still returns its columns.
        Consumer<Holder<SqlToRelConverter.Config>> keepPercentile = holder ->
                holder.set(holder.get().withTrimUnusedFields(false));
        Hook.SQL2REL_CONVERTER_CONFIG_BUILDER.add(keepPercentile);
        deregisterStockCalciteDriver();
        new PrometheusDriver().register();
    }

    public PrometheusDriver() {
        super();
    }

    @Override
    protected String getConnectStringPrefix() {
        return CONNECT_STRING_PREFIX;
    }

    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            return null;
        }
        return super.connect(toCalciteUrl(url), info);
    }

    private static String toCalciteUrl(String url) throws SQLException {
        Target target = target(url);
        return CONNECT_STRING_PREFIX
                + "unquotedCasing=unchanged;"
                + "parserFactory=org.lokrusta.sqlprometheus.driver.PrometheusParserFactory;"
                + "fun=standard,mysql;"
                + "model=inline:" + model(target.baseUrl, target.options);
    }

    private static Target target(String url) throws SQLException {
        String rest = url.substring(CONNECT_STRING_PREFIX.length());
        if (!rest.startsWith("//")) {
            throw new SQLException("Expected " + CONNECT_STRING_PREFIX + "//host:port");
        }
        String afterSlashes = rest.substring(2);
        int query = afterSlashes.indexOf('?');
        String authority = query >= 0 ? afterSlashes.substring(0, query) : afterSlashes;
        String queryString = query >= 0 ? afterSlashes.substring(query + 1) : "";
        int slash = authority.indexOf('/');
        if (slash >= 0) {
            authority = authority.substring(0, slash);
        }
        if (!authority.contains(":")) {
            throw new SQLException("Expected " + CONNECT_STRING_PREFIX + "//host:port");
        }
        return new Target("http://" + authority, options(queryString));
    }

    private static PrometheusOptions options(String queryString) throws SQLException {
        Integer maxRows = null;
        String step = null;
        Integer connectTimeout = null;
        Integer queryTimeout = null;
        Set<String> seen = new HashSet<>();
        if (!queryString.isEmpty()) {
            for (String part : queryString.split("&")) {
                if (part.isEmpty()) {
                    continue;
                }
                int separator = part.indexOf('=');
                if (separator <= 0) {
                    throw new SQLException("Expected name=value");
                }
                String name = part.substring(0, separator);
                String value = part.substring(separator + 1);
                if (!seen.add(name)) {
                    throw new SQLException(name + " is specified more than once");
                }
                try {
                    switch (name) {
                        case "maxRows" -> maxRows = PrometheusOptions.positiveInt(name, value);
                        case "step" -> step = PrometheusOptions.step(value);
                        case "connectTimeout" -> connectTimeout = PrometheusOptions.positiveInt(name, value);
                        case "queryTimeout" -> queryTimeout = PrometheusOptions.positiveInt(name, value);
                        default -> throw new SQLException("Unknown parameter " + name);
                    }
                } catch (IllegalArgumentException e) {
                    throw new SQLException(e.getMessage());
                }
            }
        }
        PrometheusOptions defaults = PrometheusOptions.defaults();
        return new PrometheusOptions(
                maxRows == null ? defaults.maxRows() : maxRows,
                step == null ? defaults.step() : step,
                connectTimeout == null ? defaults.connectTimeoutSeconds() : connectTimeout,
                queryTimeout == null ? defaults.queryTimeoutSeconds() : queryTimeout);
    }

    private static String model(String baseUrl, PrometheusOptions options) {
        return "{\"version\":\"1.0\",\"defaultSchema\":\"prometheus\",\"schemas\":[{"
                + "\"name\":\"prometheus\",\"type\":\"custom\","
                + "\"factory\":\"org.lokrusta.sqlprometheus.schema.PrometheusSchemaFactory\","
                + "\"operand\":{\"baseUrl\":\"" + baseUrl + "\""
                + ",\"maxRows\":" + options.maxRows()
                + ",\"step\":\"" + options.step() + "\""
                + ",\"connectTimeout\":" + options.connectTimeoutSeconds()
                + ",\"queryTimeout\":" + options.queryTimeoutSeconds()
                + "}}]}";
    }

    private record Target(String baseUrl, PrometheusOptions options) {
    }

    private static void deregisterStockCalciteDriver() {
        try {
            List<java.sql.Driver> stock = new ArrayList<>();
            Enumeration<java.sql.Driver> drivers = DriverManager.getDrivers();
            while (drivers.hasMoreElements()) {
                java.sql.Driver driver = drivers.nextElement();
                if (driver.getClass() == org.apache.calcite.jdbc.Driver.class) {
                    stock.add(driver);
                }
            }
            for (java.sql.Driver driver : stock) {
                DriverManager.deregisterDriver(driver);
            }
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}

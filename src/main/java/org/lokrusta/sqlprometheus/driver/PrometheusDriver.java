package org.lokrusta.sqlprometheus.driver;

import org.apache.calcite.runtime.Hook;
import org.apache.calcite.tools.Program;
import org.apache.calcite.util.Holder;
import org.lokrusta.sqlprometheus.plan.PrometheusPrograms;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

public class PrometheusDriver extends org.apache.calcite.jdbc.Driver {
    public static final String CONNECT_STRING_PREFIX = "jdbc:calcite-prometheus:";

    static {
        Consumer<Holder<Program>> program = holder -> holder.set(PrometheusPrograms.create());
        Hook.PROGRAM.add(program);
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
        return super.connect(withDefaults(url), info);
    }

    private static String withDefaults(String url) {
        StringBuilder suffix = new StringBuilder(url.substring(CONNECT_STRING_PREFIX.length()));
        if (!suffix.toString().contains("schemaFactory=")) {
            append(suffix, "schemaFactory=org.lokrusta.sqlprometheus.schema.PrometheusSchemaFactory");
            append(suffix, "schema=prometheus");
        }
        if (!suffix.toString().contains("unquotedCasing=")) {
            append(suffix, "unquotedCasing=unchanged");
        }
        if (!suffix.toString().contains("parserFactory=")) {
            append(suffix, "parserFactory=org.lokrusta.sqlprometheus.driver.PrometheusParserFactory");
        }
        return CONNECT_STRING_PREFIX + suffix;
    }

    private static void append(StringBuilder suffix, String property) {
        if (suffix.length() > 0 && suffix.charAt(suffix.length() - 1) != ';') {
            suffix.append(';');
        }
        suffix.append(property);
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

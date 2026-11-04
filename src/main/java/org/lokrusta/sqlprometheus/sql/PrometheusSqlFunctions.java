package org.lokrusta.sqlprometheus.sql;

/**
 * Заглушки для JDBC. {@code fun=} принимает только библиотеки Calcite, поэтому
 * валидатор видит PROM_* как функции схемы. Правило заменяет вызов до исполнения.
 */
public final class PrometheusSqlFunctions {
    private PrometheusSqlFunctions() {
    }

    public static double PROM_RATE(double value, String window) {
        return notPushed("PROM_RATE", value, window);
    }

    public static double PROM_IRATE(double value, String window) {
        return notPushed("PROM_IRATE", value, window);
    }

    public static double PROM_INCREASE(double value, String window) {
        return notPushed("PROM_INCREASE", value, window);
    }

    public static double PROM_DELTA(double value, String window) {
        return notPushed("PROM_DELTA", value, window);
    }

    public static double PROM_IDELTA(double value, String window) {
        return notPushed("PROM_IDELTA", value, window);
    }

    public static double PROM_CHANGES(double value, String window) {
        return notPushed("PROM_CHANGES", value, window);
    }

    private static double notPushed(String name, double value, String window) {
        throw new IllegalStateException(name + "(" + value + ", " + window + ") must be pushed into Prometheus");
    }
}

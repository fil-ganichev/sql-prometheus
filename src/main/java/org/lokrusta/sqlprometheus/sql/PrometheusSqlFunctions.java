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

    public static double PROM_DERIV(double value, String window) {
        return notPushed("PROM_DERIV", value, window);
    }

    public static double PROM_RESET(double value, String window) {
        return notPushed("PROM_RESET", value, window);
    }

    public static double PROM_MAD(double value, String window) {
        return notPushed("PROM_MAD", value, window);
    }

    public static double PROM_PREDICT_LINEAR(double value, String window, double seconds) {
        throw new IllegalStateException(
                "PROM_PREDICT_LINEAR(" + value + ", " + window + ", " + seconds + ") must be pushed into Prometheus");
    }

    public static double PROM_HOLT_WINTERS(double value, String window, double sf, double tf) {
        throw new IllegalStateException(
                "PROM_HOLT_WINTERS(" + value + ", " + window + ", " + sf + ", " + tf + ") must be pushed into Prometheus");
    }

    public static double PROM_SUM(double value) {
        return notPushed("PROM_SUM", value);
    }

    public static double PROM_AVG(double value) {
        return notPushed("PROM_AVG", value);
    }

    public static double PROM_MIN(double value) {
        return notPushed("PROM_MIN", value);
    }

    public static double PROM_MAX(double value) {
        return notPushed("PROM_MAX", value);
    }

    public static double PROM_COUNT(double value) {
        return notPushed("PROM_COUNT", value);
    }

    private static double notPushed(String name, double value, String window) {
        throw new IllegalStateException(name + "(" + value + ", " + window + ") must be pushed into Prometheus");
    }

    private static double notPushed(String name, double value) {
        throw new IllegalStateException(name + "(" + value + ") must be pushed into Prometheus");
    }
}

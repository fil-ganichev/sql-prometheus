package org.lokrusta.sqlprometheus.sql;

import org.apache.calcite.linq4j.function.Parameter;

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

    public static double PROM_RATE_WHEN(boolean comparison, String window,
                                        @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_RATE_WHEN", comparison, window, step);
    }

    public static double PROM_IRATE_WHEN(boolean comparison, String window,
                                         @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_IRATE_WHEN", comparison, window, step);
    }

    public static double PROM_INCREASE_WHEN(boolean comparison, String window,
                                            @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_INCREASE_WHEN", comparison, window, step);
    }

    public static double PROM_DELTA_WHEN(boolean comparison, String window,
                                         @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_DELTA_WHEN", comparison, window, step);
    }

    public static double PROM_IDELTA_WHEN(boolean comparison, String window,
                                          @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_IDELTA_WHEN", comparison, window, step);
    }

    public static double PROM_CHANGES_WHEN(boolean comparison, String window,
                                           @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_CHANGES_WHEN", comparison, window, step);
    }

    public static double PROM_DERIV_WHEN(boolean comparison, String window,
                                         @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_DERIV_WHEN", comparison, window, step);
    }

    public static double PROM_RESET_WHEN(boolean comparison, String window,
                                         @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_RESET_WHEN", comparison, window, step);
    }

    public static double PROM_MAD_WHEN(boolean comparison, String window,
                                       @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_MAD_WHEN", comparison, window, step);
    }

    public static double PROM_PREDICT_LINEAR_WHEN(boolean comparison, String window, double seconds,
                                                  @Parameter(name = "step", optional = true) String step) {
        throw new IllegalStateException("PROM_PREDICT_LINEAR_WHEN must be pushed into Prometheus");
    }

    public static double PROM_HOLT_WINTERS_WHEN(boolean comparison, String window, double sf, double tf,
                                                @Parameter(name = "step", optional = true) String step) {
        throw new IllegalStateException("PROM_HOLT_WINTERS_WHEN must be pushed into Prometheus");
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

    public static double PROM_AVG_OVER_TIME(double value, String window,
                                            @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_AVG_OVER_TIME", value, window, step);
    }

    public static double PROM_MIN_OVER_TIME(double value, String window,
                                            @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_MIN_OVER_TIME", value, window, step);
    }

    public static double PROM_MAX_OVER_TIME(double value, String window,
                                            @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_MAX_OVER_TIME", value, window, step);
    }

    public static double PROM_SUM_OVER_TIME(double value, String window,
                                            @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_SUM_OVER_TIME", value, window, step);
    }

    public static double PROM_COUNT_OVER_TIME(double value, String window,
                                             @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_COUNT_OVER_TIME", value, window, step);
    }

    public static double PROM_STDDEV_OVER_TIME(double value, String window,
                                               @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_STDDEV_OVER_TIME", value, window, step);
    }

    public static double PROM_STDVAR_OVER_TIME(double value, String window,
                                               @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_STDVAR_OVER_TIME", value, window, step);
    }

    public static double PROM_LAST_OVER_TIME(double value, String window,
                                             @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_LAST_OVER_TIME", value, window, step);
    }

    public static double PROM_PRESENT_OVER_TIME(double value, String window,
                                                @Parameter(name = "step", optional = true) String step) {
        return notPushedOverTime("PROM_PRESENT_OVER_TIME", value, window, step);
    }

    public static double PROM_QUANTILE_OVER_TIME(double value, String window, double phi) {
        throw new IllegalStateException(
                "PROM_QUANTILE_OVER_TIME(" + value + ", " + window + ", " + phi + ") must be pushed into Prometheus");
    }

    public static double PROM_AVG_OVER_TIME_WHEN(boolean comparison, String window,
                                                 @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_AVG_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_MIN_OVER_TIME_WHEN(boolean comparison, String window,
                                                 @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_MIN_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_MAX_OVER_TIME_WHEN(boolean comparison, String window,
                                                 @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_MAX_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_SUM_OVER_TIME_WHEN(boolean comparison, String window,
                                                 @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_SUM_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_COUNT_OVER_TIME_WHEN(boolean comparison, String window,
                                                   @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_COUNT_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_STDDEV_OVER_TIME_WHEN(boolean comparison, String window,
                                                    @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_STDDEV_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_STDVAR_OVER_TIME_WHEN(boolean comparison, String window,
                                                    @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_STDVAR_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_LAST_OVER_TIME_WHEN(boolean comparison, String window,
                                                  @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_LAST_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_PRESENT_OVER_TIME_WHEN(boolean comparison, String window,
                                                     @Parameter(name = "step", optional = true) String step) {
        return notPushedWhen("PROM_PRESENT_OVER_TIME_WHEN", comparison, window, step);
    }

    public static double PROM_QUANTILE_OVER_TIME_WHEN(boolean comparison, String window, double phi,
                                                      @Parameter(name = "step", optional = true) String step) {
        throw new IllegalStateException("PROM_QUANTILE_OVER_TIME_WHEN must be pushed into Prometheus");
    }

    public static double PROM_OFFSET(double value, String offset) {
        throw new IllegalStateException("PROM_OFFSET(" + value + ", " + offset + ") must be pushed into Prometheus");
    }

    public static double PROM_TOPK(double k, double value) {
        throw new IllegalStateException("PROM_TOPK(" + k + ", " + value + ") must be pushed into Prometheus");
    }

    public static double PROM_BOTTOMK(double k, double value) {
        throw new IllegalStateException("PROM_BOTTOMK(" + k + ", " + value + ") must be pushed into Prometheus");
    }

    public static double PROM_LABEL_REPLACE(double value, String destination, String replacement, String source, String regex) {
        throw new IllegalStateException("PROM_LABEL_REPLACE must be pushed into Prometheus");
    }

    private static double notPushed(String name, double value, String window) {
        throw new IllegalStateException(name + "(" + value + ", " + window + ") must be pushed into Prometheus");
    }

    private static double notPushed(String name, double value) {
        throw new IllegalStateException(name + "(" + value + ") must be pushed into Prometheus");
    }

    private static double notPushedOverTime(String name, double value, String window, String step) {
        throw new IllegalStateException(name + "(" + value + ", " + window + ", " + step + ") must be pushed into Prometheus");
    }

    private static double notPushedWhen(String name, boolean comparison, String window, String step) {
        throw new IllegalStateException(name + " must be pushed into Prometheus");
    }
}

package org.lokrusta.sqlprometheus.exec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Continuous percentile of the values Calcite already read.
 * {@code PERCENTILE_CONT} ordered by {@code le} is a histogram and stays in Prometheus.
 * Ordered by any other column, the same function runs here.
 */
public final class PercentileCont {
    private PercentileCont() {
    }

    public static Accumulator init() {
        return new Accumulator();
    }

    public static Accumulator add(Accumulator accumulator, BigDecimal phi, Double value) {
        if (accumulator.phi == null && phi != null) {
            accumulator.phi = phi.doubleValue();
        }
        if (value != null) {
            accumulator.values.add(value);
        }
        return accumulator;
    }

    public static Double result(Accumulator accumulator) {
        if (accumulator.phi == null) {
            return null;
        }
        return continuous(accumulator.values, accumulator.phi);
    }

    public static Double continuous(List<Double> values, double phi) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        double rank = 1.0 + phi * (sorted.size() - 1);
        if (rank < 1.0) {
            rank = 1.0;
        }
        if (rank > sorted.size()) {
            rank = sorted.size();
        }
        int low = (int) Math.floor(rank);
        int high = (int) Math.ceil(rank);
        double lower = sorted.get(low - 1);
        double upper = sorted.get(high - 1);
        if (low == high) {
            return lower;
        }
        return lower + (rank - low) * (upper - lower);
    }

    public static final class Accumulator {
        private Double phi;
        private final List<Double> values = new ArrayList<>();
    }
}

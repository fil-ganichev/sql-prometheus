package org.lokrusta.sqlprometheus.exec;

public record PrometheusOptions(int maxRows,
                                String step,
                                int connectTimeoutSeconds,
                                int queryTimeoutSeconds,
                                int schemaCacheMaxBytes,
                                int schemaCacheTtlSeconds,
                                int planCacheMaxBytes,
                                int planCacheTtlSeconds) {
    public static final String DEFAULT_STEP = "15s";
    public static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 10;
    public static final int DEFAULT_QUERY_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_SCHEMA_CACHE_MAX_BYTES = 4 * 1024 * 1024;
    public static final int DEFAULT_SCHEMA_CACHE_TTL_SECONDS = 600;
    public static final int DEFAULT_PLAN_CACHE_MAX_BYTES = 0;
    public static final int DEFAULT_PLAN_CACHE_TTL_SECONDS = 600;

    public static PrometheusOptions defaults() {
        return new PrometheusOptions(
                PrometheusRowIterator.DEFAULT_MAX_ROWS,
                DEFAULT_STEP,
                DEFAULT_CONNECT_TIMEOUT_SECONDS,
                DEFAULT_QUERY_TIMEOUT_SECONDS,
                DEFAULT_SCHEMA_CACHE_MAX_BYTES,
                DEFAULT_SCHEMA_CACHE_TTL_SECONDS,
                DEFAULT_PLAN_CACHE_MAX_BYTES,
                DEFAULT_PLAN_CACHE_TTL_SECONDS);
    }

    public static int positiveInt(String name, String raw) {
        return boundedInt(name, raw, false);
    }

    public static int nonNegativeInt(String name, String raw) {
        return boundedInt(name, raw, true);
    }

    private static int boundedInt(String name, String raw, boolean zeroAllowed) {
        String expectation = zeroAllowed ? "zero or a positive integer" : "a positive integer";
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0 || (value == 0 && !zeroAllowed)) {
                throw new IllegalArgumentException(name + " must be " + expectation);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be " + expectation);
        }
    }

    public static String step(String raw) {
        String value = raw.trim();
        boolean plainSeconds = value.matches("\\d+(\\.\\d+)?");
        boolean duration = value.matches("(\\d+(ms|s|m|h|d|w|y))+");
        if (!plainSeconds && !duration) {
            throw new IllegalArgumentException("step must be a positive duration, for example 15s");
        }
        if (value.matches("0+(\\.0+)?") || value.matches("(0+(ms|s|m|h|d|w|y))+")) {
            throw new IllegalArgumentException("step must be a positive duration, for example 15s");
        }
        return plainSeconds ? value + "s" : value;
    }
}

package org.lokrusta.sqlprometheus.benchmark;

import java.util.List;

public record QueryPair(String name, String sqlTemplate, String promql) {
    public static final List<QueryPair> ALL = List.of(
            new QueryPair(
                    "rate_create_order_success",
                    """
                    SELECT "timestamp", PROM_RATE("value", '5m')
                    FROM operation_code_total
                    WHERE process_action = 'createOrder'
                      AND code = 'SUCCESS'
                      AND "timestamp" >= TIMESTAMP '__START__'
                      AND "timestamp" <= TIMESTAMP '__END__'
                    """,
                    "rate(operation_code_total{process_action=\"createOrder\",code=\"SUCCESS\"}[5m])"),
            new QueryPair(
                    "irate_get_categories_success",
                    """
                    SELECT "timestamp", PROM_IRATE("value", '5m')
                    FROM operation_time_seconds_count
                    WHERE process_action = 'getCategories'
                      AND code = 'SUCCESS'
                      AND "timestamp" >= TIMESTAMP '__START__'
                      AND "timestamp" <= TIMESTAMP '__END__'
                    """,
                    "irate(operation_time_seconds_count{process_action=\"getCategories\",code=\"SUCCESS\"}[5m])"),
            new QueryPair(
                    "increase_get_user_orders",
                    """
                    SELECT "timestamp", PROM_INCREASE("value", '5m')
                    FROM operation_code_total
                    WHERE process_action = 'getUserOrders'
                      AND code = 'SUCCESS'
                      AND "timestamp" >= TIMESTAMP '__START__'
                      AND "timestamp" <= TIMESTAMP '__END__'
                    """,
                    "increase(operation_code_total{process_action=\"getUserOrders\",code=\"SUCCESS\"}[5m])"),
            new QueryPair(
                    "sum_rate_operation_code",
                    """
                    SELECT "timestamp", PROM_SUM(PROM_RATE("value", '5m'))
                    FROM operation_code_total
                    WHERE "timestamp" >= TIMESTAMP '__START__'
                      AND "timestamp" <= TIMESTAMP '__END__'
                    """,
                    "sum(rate(operation_code_total[5m]))"));

    public static QueryPair named(String name) {
        for (QueryPair pair : ALL) {
            if (pair.name.equals(name)) {
                return pair;
            }
        }
        throw new IllegalArgumentException("Unknown query pair " + name);
    }

    public String sql(RecordedWindow window) {
        return sqlTemplate.stripIndent()
                .replace("__START__", window.startLiteral())
                .replace("__END__", window.endLiteral());
    }
}

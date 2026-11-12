package org.lokrusta.sqlprometheus.grafana;

final class QueryRejected extends RuntimeException {
    QueryRejected(String message) {
        super(message);
    }
}

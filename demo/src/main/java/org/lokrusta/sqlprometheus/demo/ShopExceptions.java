package org.lokrusta.sqlprometheus.demo;

/**
 * Failures of the shop service. A stale version is a conflict and is not retried.
 */
public final class ShopExceptions {
    private ShopExceptions() {
    }

    public static final class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    public static final class Conflict extends RuntimeException {
        public Conflict(String message) {
            super(message);
        }
    }

    public static final class InsufficientStock extends RuntimeException {
        public InsufficientStock(String message) {
            super(message);
        }
    }
}

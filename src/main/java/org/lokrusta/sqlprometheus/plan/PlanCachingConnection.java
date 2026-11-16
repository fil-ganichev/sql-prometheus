package org.lokrusta.sqlprometheus.plan;

import org.lokrusta.sqlprometheus.exec.PrometheusOptions;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

public final class PlanCachingConnection implements InvocationHandler {
    private final Connection delegate;
    private final PlanCache cache;
    private Connection proxy;

    private PlanCachingConnection(Connection delegate, PlanCache cache) {
        this.delegate = delegate;
        this.cache = cache;
    }

    public static Connection wrap(Connection delegate, PrometheusOptions options) {
        PlanCachingConnection handler = new PlanCachingConnection(delegate, new PlanCache(options));
        handler.proxy = (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                handler);
        return handler.proxy;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        int argc = args == null ? 0 : args.length;
        if ("unwrap".equals(name) && argc == 1) {
            Class<?> type = (Class<?>) args[0];
            if (type.isInstance(this.proxy)) {
                return this.proxy;
            }
            return delegate.unwrap(type);
        }
        if ("isWrapperFor".equals(name) && argc == 1) {
            Class<?> type = (Class<?>) args[0];
            return type.isInstance(this.proxy) || delegate.isWrapperFor(type);
        }
        if ("createStatement".equals(name)) {
            int type = ResultSet.TYPE_FORWARD_ONLY;
            int concurrency = ResultSet.CONCUR_READ_ONLY;
            int holdability = delegate.getHoldability();
            if (argc >= 2) {
                type = (Integer) args[0];
                concurrency = (Integer) args[1];
            }
            if (argc >= 3) {
                holdability = (Integer) args[2];
            }
            return statement(type, concurrency, holdability);
        }
        if ("prepareStatement".equals(name) && argc == 1 && args[0] instanceof String sql) {
            return cache.open(delegate, sql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY,
                    delegate.getHoldability());
        }
        return call(method, delegate, args);
    }

    private Statement statement(int type, int concurrency, int holdability) throws SQLException {
        Statement plain = delegate.createStatement(type, concurrency, holdability);
        CachingStatement handler = new CachingStatement(this.proxy, delegate, cache, plain, type, concurrency, holdability);
        return (Statement) Proxy.newProxyInstance(
                Statement.class.getClassLoader(),
                new Class<?>[]{Statement.class},
                handler);
    }

    private static Object call(Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static final class CachingStatement implements InvocationHandler {
        private final Connection userConnection;
        private final Connection delegate;
        private final PlanCache cache;
        private final Statement plain;
        private final int type;
        private final int concurrency;
        private final int holdability;
        private Statement active;
        private boolean closed;

        private CachingStatement(Connection userConnection,
                                 Connection delegate,
                                 PlanCache cache,
                                 Statement plain,
                                 int type,
                                 int concurrency,
                                 int holdability) {
            this.userConnection = userConnection;
            this.delegate = delegate;
            this.cache = cache;
            this.plain = plain;
            this.type = type;
            this.concurrency = concurrency;
            this.holdability = holdability;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            int argc = args == null ? 0 : args.length;
            if ("close".equals(name) && argc == 0) {
                close();
                return null;
            }
            if ("isClosed".equals(name) && argc == 0) {
                return closed;
            }
            if ("getConnection".equals(name) && argc == 0) {
                return userConnection;
            }
            if (closed) {
                throw new SQLException("Statement closed");
            }
            if ("executeQuery".equals(name) && argc == 1 && args[0] instanceof String sql) {
                return query(sql);
            }
            if ("execute".equals(name) && argc == 1 && args[0] instanceof String sql) {
                query(sql);
                return true;
            }
            return call(method, plain, args);
        }

        private ResultSet query(String sql) throws SQLException {
            closeActive();
            PreparedStatement prepared = cache.open(delegate, sql, type, concurrency, holdability);
            active = prepared;
            try {
                return prepared.executeQuery();
            } catch (SQLException e) {
                active = null;
                prepared.close();
                throw e;
            }
        }

        private void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;
            SQLException failure = null;
            try {
                closeActive();
            } catch (SQLException e) {
                failure = e;
            }
            try {
                plain.close();
            } catch (SQLException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private void closeActive() throws SQLException {
            Statement statement = active;
            active = null;
            if (statement != null) {
                statement.close();
            }
        }
    }
}

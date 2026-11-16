package org.lokrusta.sqlprometheus.plan;

import org.apache.calcite.avatica.AvaticaConnection;
import org.apache.calcite.avatica.AvaticaFactory;
import org.apache.calcite.avatica.AvaticaStatement;
import org.apache.calcite.avatica.Meta;
import org.apache.calcite.avatica.NoSuchStatementException;
import org.apache.calcite.server.CalciteServer;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

final class CalcitePlans {
    private CalcitePlans() {
    }

    static Meta.Signature signature(PreparedStatement statement) throws SQLException {
        try {
            Method method = AvaticaStatement.class.getDeclaredMethod("getSignature");
            method.setAccessible(true);
            return (Meta.Signature) method.invoke(statement);
        } catch (InvocationTargetException e) {
            throw failure(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw failure(e);
        }
    }

    static PreparedStatement replay(Connection connection,
                                    Meta.Signature signature,
                                    int type,
                                    int concurrency,
                                    int holdability) throws SQLException {
        AvaticaConnection avatica = (AvaticaConnection) connection;
        try {
            Meta.StatementHandle handle = meta(avatica).createStatement(avatica.handle);
            server(avatica).getStatement(handle).setSignature(signature);
            handle.signature = signature;
            return factory(avatica).newPreparedStatement(
                    avatica, handle, signature, type, concurrency, holdability);
        } catch (NoSuchStatementException e) {
            throw failure(e);
        }
    }

    private static Meta meta(AvaticaConnection connection) throws SQLException {
        return (Meta) field(connection, AvaticaConnection.class, "meta");
    }

    private static AvaticaFactory factory(AvaticaConnection connection) throws SQLException {
        return (AvaticaFactory) field(connection, AvaticaConnection.class, "factory");
    }

    private static CalciteServer server(AvaticaConnection connection) throws SQLException {
        Class<?> type = connection.getClass();
        while (type != null) {
            try {
                return (CalciteServer) field(connection, type, "server");
            } catch (SQLException e) {
                if (!(e.getCause() instanceof NoSuchFieldException)) {
                    throw e;
                }
                type = type.getSuperclass();
            }
        }
        throw new SQLException("Calcite server is not available");
    }

    private static Object field(Object target, Class<?> type, String name) throws SQLException {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw failure(e);
        }
    }

    private static SQLException failure(Throwable cause) {
        if (cause instanceof SQLException sql) {
            return sql;
        }
        return new SQLException(cause == null ? "Failed to reuse a Prometheus plan" : cause.getMessage(), cause);
    }
}

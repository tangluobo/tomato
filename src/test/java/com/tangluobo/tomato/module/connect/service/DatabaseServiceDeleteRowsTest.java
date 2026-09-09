package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectType;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceDeleteRowsTest {

    @Test
    void commitsTypedPrimaryKeyDeletesAsOneTransaction() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1, 1);

        int deleted = deleteTwoRows(jdbc.connection());

        assertAll(
                () -> assertEquals(2, deleted),
                () -> assertEquals(List.of(
                        new Binding("setObject", 1, "11", Types.INTEGER),
                        new Binding("setObject", 1, "12", Types.INTEGER)), jdbc.bindings),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount),
                () -> assertEquals(List.of(false, true), jdbc.autoCommitChanges),
                () -> assertTrue(jdbc.autoCommit));
    }

    @Test
    void rollsBackWhenAnyPrimaryKeyDeleteDoesNotHitOneRow() {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1, 0);

        SQLException error = assertThrows(SQLException.class,
                () -> deleteTwoRows(jdbc.connection()));

        assertAll(
                () -> assertTrue(error.getMessage().contains("第 2 行")),
                () -> assertTrue(error.getMessage().contains("影响了 0 行")),
                () -> assertEquals(0, jdbc.commitCount),
                () -> assertEquals(1, jdbc.rollbackCount),
                () -> assertEquals(List.of(false, true), jdbc.autoCommitChanges),
                () -> assertTrue(jdbc.autoCommit));
    }

    @Test
    void refusesToCommitAnExistingManualTransaction() {
        ScriptedJdbc jdbc = new ScriptedJdbc(false, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> deleteTwoRows(jdbc.connection()));

        assertAll(
                () -> assertTrue(error.getMessage().contains("手动事务")),
                () -> assertEquals(0, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount),
                () -> assertTrue(jdbc.autoCommitChanges.isEmpty()),
                () -> assertFalse(jdbc.autoCommit));
    }

    private static int deleteTwoRows(Connection connection) throws Exception {
        List<ObservableList<String>> rows = List.of(
                FXCollections.observableArrayList("11", "first"),
                FXCollections.observableArrayList("12", "second"));
        return DatabaseService.deleteRowsByPrimaryKeysInTransaction(
                connection, ConnectType.POSTGRESQL, "app", "public", "users",
                List.of("id"), List.of("id", "name"), List.of(Types.INTEGER, Types.VARCHAR), rows);
    }

    private static final class ScriptedJdbc implements InvocationHandler {
        private final int[] batchResults;
        private final List<Boolean> autoCommitChanges = new ArrayList<>();
        private final List<Binding> bindings = new ArrayList<>();
        private boolean autoCommit;
        private int commitCount;
        private int rollbackCount;

        private ScriptedJdbc(boolean autoCommit, int... batchResults) {
            this.autoCommit = autoCommit;
            this.batchResults = batchResults;
        }

        private Connection connection() {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, this);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    autoCommitChanges.add(autoCommit);
                    yield null;
                }
                case "prepareStatement" -> preparedStatement();
                case "commit" -> {
                    commitCount++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCount++;
                    yield null;
                }
                case "isClosed" -> false;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "ScriptedJdbcConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
        }

        private PreparedStatement preparedStatement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "executeBatch" -> batchResults;
                case "setString" -> {
                    bindings.add(new Binding("setString", (int) args[0], args[1], Types.VARCHAR));
                    yield null;
                }
                case "setNull" -> {
                    bindings.add(new Binding("setNull", (int) args[0], null, (int) args[1]));
                    yield null;
                }
                case "setObject" -> {
                    bindings.add(new Binding("setObject", (int) args[0], args[1], (int) args[2]));
                    yield null;
                }
                case "close", "clearParameters", "addBatch" -> null;
                case "isClosed" -> false;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "ScriptedPreparedStatement";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(),
                    new Class<?>[]{PreparedStatement.class}, handler);
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) return null;
            if (returnType == boolean.class) return false;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0F;
            if (returnType == double.class) return 0D;
            if (returnType == char.class) return '\0';
            throw new AssertionError("Unexpected primitive: " + returnType);
        }
    }

    private record Binding(String method, int parameterIndex, Object value, int sqlType) {
    }
}

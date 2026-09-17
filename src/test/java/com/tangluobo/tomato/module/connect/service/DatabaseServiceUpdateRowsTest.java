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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceUpdateRowsTest {

    @Test
    void commitsOnlyAfterEveryRowUpdatesExactlyOnce() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1, 1);

        int updated = updateTwoRows(jdbc.connection());

        assertAll(
                () -> assertEquals(2, updated),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount),
                () -> assertEquals(List.of(false, true), jdbc.autoCommitChanges),
                () -> assertTrue(jdbc.autoCommit),
                () -> assertEquals(2, jdbc.sql.size()));
    }

    @Test
    void rollsBackTheWholeBatchWhenOneRowIsNotFound() {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1, 0);

        SQLException error = assertThrows(SQLException.class,
                () -> updateTwoRows(jdbc.connection()));

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
                () -> updateTwoRows(jdbc.connection()));

        assertAll(
                () -> assertTrue(error.getMessage().contains("手动事务")),
                () -> assertEquals(0, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount),
                () -> assertTrue(jdbc.autoCommitChanges.isEmpty()),
                () -> assertFalse(jdbc.autoCommit),
                () -> assertTrue(jdbc.sql.isEmpty()));
    }

    @Test
    void bindsPostgresValuesWithTheirJdbcTypes() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1);
        String uuid = "123e4567-e89b-12d3-a456-426614174000";

        int updated = DatabaseService.updateRowsInTransaction(
                jdbc.connection(), ConnectType.POSTGRESQL, "app", "public", "events",
                List.of("id"), List.of("id", "event_date"),
                List.of(Types.OTHER, Types.DATE),
                List.of(FXCollections.observableArrayList(uuid, "2026-09-08")),
                List.of(FXCollections.observableArrayList(uuid, "2026-09-07")),
                List.of(Set.of(1)), null);

        assertAll(
                () -> assertEquals(1, updated),
                () -> assertEquals(List.of(
                        new Binding("setObject", 1, "2026-09-08", Types.DATE),
                        new Binding("setObject", 2, uuid, Types.OTHER)), jdbc.bindings),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount));
    }

    @Test
    void matchesTypedMySqlTextColumnsWithBinarySemanticsWithoutAPrimaryKey() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1);

        int updated = DatabaseService.updateRowsInTransaction(
                jdbc.connection(), ConnectType.MYSQL, "app", null, "labels",
                List.of(), List.of("name", "note"),
                List.of(Types.VARCHAR, Types.VARCHAR),
                List.of(FXCollections.observableArrayList("alice", "new")),
                List.of(FXCollections.observableArrayList("alice", "old")),
                List.of(Set.of(1)), null);

        assertAll(
                () -> assertEquals(1, updated),
                () -> assertEquals(1, jdbc.sql.size()),
                () -> assertTrue(jdbc.sql.getFirst().contains(
                        "WHERE BINARY `name` = BINARY ? AND BINARY `note` = BINARY ? LIMIT 1")),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount));
    }

    @Test
    void updatesOnePostgresPhysicalRowWithoutAPrimaryKey() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1);

        int updated = DatabaseService.updateRowsInTransaction(
                jdbc.connection(), ConnectType.POSTGRESQL, "app", "public", "labels",
                List.of(), List.of("name", "note"),
                List.of(Types.VARCHAR, Types.VARCHAR),
                List.of(FXCollections.observableArrayList("alice", "new")),
                List.of(FXCollections.observableArrayList("alice", "old")),
                List.of(Set.of(1)), null);

        assertAll(
                () -> assertEquals(1, updated),
                () -> assertEquals(1, jdbc.sql.size()),
                () -> assertTrue(jdbc.sql.getFirst().contains(
                        "WHERE ctid = (SELECT ctid FROM \"public\".\"labels\" "
                                + "WHERE \"name\" = ? AND \"note\" = ? LIMIT 1)")),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount));
    }

    @Test
    void updatesOneOraclePhysicalRowWithoutAPrimaryKey() throws Exception {
        ScriptedJdbc jdbc = new ScriptedJdbc(true, 1);

        int updated = DatabaseService.updateRowsInTransaction(
                jdbc.connection(), ConnectType.ORACLE, "APP", null, "LABELS",
                List.of(), List.of("NAME", "NOTE"),
                List.of(Types.VARCHAR, Types.VARCHAR),
                List.of(FXCollections.observableArrayList("alice", "new")),
                List.of(FXCollections.observableArrayList("alice", "old")),
                List.of(Set.of(1)), null);

        assertAll(
                () -> assertEquals(1, updated),
                () -> assertEquals(1, jdbc.sql.size()),
                () -> assertTrue(jdbc.sql.getFirst().contains(
                        "WHERE ROWID = (SELECT ROWID FROM \"APP\".\"LABELS\" "
                                + "WHERE \"NAME\" = ? AND \"NOTE\" = ? AND ROWNUM = 1)")),
                () -> assertEquals(1, jdbc.commitCount),
                () -> assertEquals(0, jdbc.rollbackCount));
    }

    private static int updateTwoRows(Connection connection) throws Exception {
        List<ObservableList<String>> currentRows = List.of(
                FXCollections.observableArrayList("1", "new-a"),
                FXCollections.observableArrayList("2", "new-b"));
        List<ObservableList<String>> originalRows = List.of(
                FXCollections.observableArrayList("1", "old-a"),
                FXCollections.observableArrayList("2", "old-b"));

        return DatabaseService.updateRowsInTransaction(
                connection, ConnectType.MYSQL, "app", null, "users",
                List.of("id"), List.of("id", "name"),
                currentRows, originalRows, List.of(Set.of(1), Set.of(1)), null);
    }

    private static final class ScriptedJdbc implements InvocationHandler {
        private final Deque<Integer> updateCounts = new ArrayDeque<>();
        private final List<Boolean> autoCommitChanges = new ArrayList<>();
        private final List<String> sql = new ArrayList<>();
        private final List<Binding> bindings = new ArrayList<>();
        private boolean autoCommit;
        private int commitCount;
        private int rollbackCount;

        private ScriptedJdbc(boolean autoCommit, int... updateCounts) {
            this.autoCommit = autoCommit;
            for (int updateCount : updateCounts) this.updateCounts.add(updateCount);
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
                case "prepareStatement" -> {
                    sql.add((String) args[0]);
                    yield preparedStatement();
                }
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
                case "executeUpdate" -> updateCounts.removeFirst();
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
                case "close", "clearParameters" -> null;
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

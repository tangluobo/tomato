package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceCopyTableTransferTest {

    @Test
    void streamsNativeJdbcValuesAndCommitsOneBatch() throws Exception {
        Timestamp timestamp = Timestamp.valueOf("2026-09-21 08:30:00");
        byte[] payload = new byte[]{0, (byte) 0xff, 42};
        SourceJdbc source = new SourceJdbc(new Object[][]{
                {1L, timestamp, payload, null},
                {2L, Timestamp.valueOf("2026-09-21 08:31:00"), new byte[]{7}, "ok"}
        });
        TargetJdbc target = new TargetJdbc(false);
        List<Long> progress = new ArrayList<>();

        long copied = DatabaseService.transferRows(
                source.connection(), ConnectType.MYSQL, "`student`.`source_table`",
                target.connection(), ConnectType.MYSQL, "`student`.`target_table`",
                progress::add);

        assertEquals(2, copied);
        assertEquals(List.of(2L), progress);
        assertEquals(500, source.fetchSize);
        assertEquals(1, target.commitCount);
        assertEquals(0, target.rollbackCount);
        assertEquals(List.of(false, true), target.autoCommitChanges);
        assertEquals(120, target.queryTimeout);
        assertEquals(2, target.batchRows);

        Binding id = target.bindings.get(0);
        Binding time = target.bindings.get(1);
        Binding blob = target.bindings.get(2);
        Binding nullText = target.bindings.get(3);
        assertEquals("setObject", id.method());
        assertInstanceOf(Long.class, id.value());
        assertEquals(Types.BIGINT, id.sqlType());
        assertEquals("setObject", time.method());
        assertEquals(timestamp, time.value());
        assertEquals(Types.TIMESTAMP, time.sqlType());
        assertEquals("setBytes", blob.method());
        assertArrayEquals(payload, (byte[]) blob.value());
        assertEquals("setNull", nullText.method());
        assertEquals(Types.LONGVARCHAR, nullText.sqlType());
        Binding text = target.bindings.get(7);
        assertEquals("setString", text.method());
        assertEquals("ok", text.value());
        assertEquals(Types.LONGVARCHAR, text.sqlType());
    }

    @Test
    void rollsBackCurrentBatchAndReportsItsRowRange() {
        SourceJdbc source = new SourceJdbc(new Object[][]{{1L, null, null, null}});
        TargetJdbc target = new TargetJdbc(true);

        SQLException error = assertThrows(SQLException.class, () -> DatabaseService.transferRows(
                source.connection(), ConnectType.MYSQL, "`student`.`source_table`",
                target.connection(), ConnectType.MYSQL, "`student`.`target_table`", null));

        assertTrue(error.getMessage().contains("已提交 0 行"));
        assertTrue(error.getMessage().contains("第 1-1 行批次失败"));
        assertEquals(0, target.commitCount);
        assertEquals(1, target.rollbackCount);
        assertEquals(List.of(false, true), target.autoCommitChanges);
    }

    private static final class SourceJdbc {
        private static final List<String> COLUMN_NAMES = List.of("id", "created_at", "payload", "note");
        private static final List<Integer> COLUMN_TYPES = List.of(
                Types.BIGINT, Types.TIMESTAMP, Types.BLOB, Types.LONGVARCHAR);
        private static final List<String> TYPE_NAMES = List.of(
                "BIGINT", "TIMESTAMP", "BLOB", "JSON");

        private final Object[][] rows;
        private int fetchSize;

        private SourceJdbc(Object[][] rows) {
            this.rows = rows;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> statement();
                case "close" -> null;
                case "isClosed" -> false;
                case "toString" -> "SourceConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setFetchSize" -> {
                    fetchSize = (int) args[0];
                    yield null;
                }
                case "executeQuery" -> resultSet();
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private ResultSet resultSet() {
            int[] row = {-1};
            return proxy(ResultSet.class, (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++row[0] < rows.length;
                case "getObject" -> rows[row[0]][(int) args[0] - 1];
                case "getBytes" -> (byte[]) rows[row[0]][(int) args[0] - 1];
                case "getString" -> {
                    Object value = rows[row[0]][(int) args[0] - 1];
                    yield value == null ? null : value.toString();
                }
                case "getMetaData" -> metadata(COLUMN_NAMES, COLUMN_TYPES, TYPE_NAMES);
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static final class TargetJdbc {
        private final List<Binding> bindings = new ArrayList<>();
        private final List<Boolean> autoCommitChanges = new ArrayList<>();
        private final boolean failBatch;
        private boolean autoCommit = true;
        private int commitCount;
        private int rollbackCount;
        private int queryTimeout;
        private int batchRows;

        private TargetJdbc(boolean failBatch) {
            this.failBatch = failBatch;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "getAutoCommit" -> autoCommit;
                case "setAutoCommit" -> {
                    autoCommit = (boolean) args[0];
                    autoCommitChanges.add(autoCommit);
                    yield null;
                }
                case "createStatement" -> metadataStatement();
                case "prepareStatement" -> insertStatement();
                case "commit" -> {
                    commitCount++;
                    yield null;
                }
                case "rollback" -> {
                    rollbackCount++;
                    yield null;
                }
                case "close" -> null;
                case "isClosed" -> false;
                case "toString" -> "TargetConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            });
        }

        private Statement metadataStatement() {
            return proxy(Statement.class, (proxy, method, args) -> switch (method.getName()) {
                case "executeQuery" -> proxy(ResultSet.class, (rsProxy, rsMethod, rsArgs) -> switch (rsMethod.getName()) {
                    case "next" -> false;
                    case "getMetaData" -> metadata(
                            SourceJdbc.COLUMN_NAMES, SourceJdbc.COLUMN_TYPES, SourceJdbc.TYPE_NAMES);
                    case "close" -> null;
                    default -> defaultValue(rsMethod.getReturnType());
                });
                case "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }

        private PreparedStatement insertStatement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setQueryTimeout" -> {
                    queryTimeout = (int) args[0];
                    yield null;
                }
                case "setObject" -> {
                    bindings.add(new Binding("setObject", (int) args[0], args[1], (int) args[2]));
                    yield null;
                }
                case "setBytes" -> {
                    bindings.add(new Binding("setBytes", (int) args[0],
                            Arrays.copyOf((byte[]) args[1], ((byte[]) args[1]).length), Types.VARBINARY));
                    yield null;
                }
                case "setString" -> {
                    int parameter = (int) args[0];
                    bindings.add(new Binding("setString", parameter, args[1],
                            SourceJdbc.COLUMN_TYPES.get(parameter - 1)));
                    yield null;
                }
                case "setNull" -> {
                    bindings.add(new Binding("setNull", (int) args[0], null, (int) args[1]));
                    yield null;
                }
                case "addBatch" -> {
                    batchRows++;
                    yield null;
                }
                case "executeBatch" -> {
                    if (failBatch) throw new SQLException("simulated batch failure");
                    int[] counts = new int[batchRows];
                    Arrays.fill(counts, 1);
                    yield counts;
                }
                case "clearBatch", "close" -> null;
                default -> defaultValue(method.getReturnType());
            });
        }
    }

    private static ResultSetMetaData metadata(List<String> names, List<Integer> types,
                                              List<String> typeNames) {
        return proxy(ResultSetMetaData.class, (proxy, method, args) -> switch (method.getName()) {
            case "getColumnCount" -> names.size();
            case "getColumnLabel", "getColumnName" -> names.get((int) args[0] - 1);
            case "getColumnType" -> types.get((int) args[0] - 1);
            case "getColumnTypeName" -> typeNames.get((int) args[0] - 1);
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
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

    private record Binding(String method, int parameterIndex, Object value, int sqlType) {
    }
}

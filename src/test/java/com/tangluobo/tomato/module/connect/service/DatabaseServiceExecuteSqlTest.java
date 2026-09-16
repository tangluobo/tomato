package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.MultiStatementResult;
import com.tangluobo.tomato.module.connect.TableRowData;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.Types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServiceExecuteSqlTest {

    @Test
    void directSqlReturnsAllRowsWithoutSettingAJdbcLimit() {
        QueryJdbc jdbc = new QueryJdbc(1_001);

        MultiStatementResult result = DatabaseService.executeMultiSqlQuery(
                jdbc.connection(), "SELECT id FROM example");

        TableRowData data = result.getResults().getFirst().getResultData();
        assertTrue(result.getResults().getFirst().isSuccess());
        assertFalse(jdbc.maxRowsWasSet);
        assertEquals(1_001, data.getRows().size());
        assertEquals(1_001, data.getTotalCount());
        assertEquals("1001", data.getRows().getLast().getFirst());
    }

    private static final class QueryJdbc {
        private final int rowCount;
        private boolean maxRowsWasSet;

        private QueryJdbc(int rowCount) {
            this.rowCount = rowCount;
        }

        private Connection connection() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "createStatement" -> statement();
                case "close" -> null;
                case "isClosed" -> false;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "QueryJdbcConnection";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
            return proxy(Connection.class, handler);
        }

        private Statement statement() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setMaxRows" -> {
                    maxRowsWasSet = true;
                    yield null;
                }
                case "execute" -> true;
                case "getResultSet" -> resultSet();
                case "close" -> null;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "QueryJdbcStatement";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
            return proxy(Statement.class, handler);
        }

        private ResultSet resultSet() {
            int[] currentRow = {0};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> ++currentRow[0] <= rowCount;
                case "getString" -> Integer.toString(currentRow[0]);
                case "getMetaData" -> metadata();
                case "close" -> null;
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "QueryJdbcResultSet";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
            return proxy(ResultSet.class, handler);
        }

        private ResultSetMetaData metadata() {
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "getColumnCount" -> 1;
                case "getColumnLabel", "getColumnName" -> "id";
                case "getColumnType" -> Types.INTEGER;
                case "getTableName" -> "example";
                case "getSchemaName", "getCatalogName" -> "";
                case "unwrap" -> null;
                case "isWrapperFor" -> false;
                case "toString" -> "QueryJdbcMetadata";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> defaultValue(method.getReturnType());
            };
            return proxy(ResultSetMetaData.class, handler);
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
    }
}

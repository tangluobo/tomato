package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseServicePrimaryKeyTest {

    @Test
    void usesPostgresCatalogWhenJdbcMetadataMissesCompositePrimaryKey() throws Exception {
        PrimaryKeyJdbc jdbc = new PrimaryKeyJdbc(List.of(), List.of("tenant_id", "event_id"));

        List<String> primaryKeys = DatabaseService.readPrimaryKeys(
                jdbc.connection(), ConnectType.POSTGRESQL, null, "audit", "events");

        assertAll(
                () -> assertEquals(List.of("tenant_id", "event_id"), primaryKeys),
                () -> assertTrue(jdbc.sql.contains("pg_catalog.pg_constraint")),
                () -> assertTrue(jdbc.sql.contains("WITH ORDINALITY")),
                () -> assertEquals(List.of("audit", "events"), jdbc.parameters));
    }

    @Test
    void usesOracleCatalogWhenJdbcMetadataMissesPrimaryKey() throws Exception {
        PrimaryKeyJdbc jdbc = new PrimaryKeyJdbc(List.of(), List.of("ORDER_ID"));

        List<String> primaryKeys = DatabaseService.readPrimaryKeys(
                jdbc.connection(), ConnectType.ORACLE, null, "SALES", "ORDERS");

        assertAll(
                () -> assertEquals(List.of("ORDER_ID"), primaryKeys),
                () -> assertTrue(jdbc.sql.contains("all_constraints")),
                () -> assertTrue(jdbc.sql.contains("all_cons_columns")),
                () -> assertEquals(List.of("SALES", "ORDERS"), jdbc.parameters));
    }

    @Test
    void usesMySqlInformationSchemaWhenJdbcMetadataMissesPrimaryKey() throws Exception {
        PrimaryKeyJdbc jdbc = new PrimaryKeyJdbc(List.of(), List.of("id"));

        List<String> primaryKeys = DatabaseService.readPrimaryKeys(
                jdbc.connection(), ConnectType.MYSQL, "app", null, "users");

        assertAll(
                () -> assertEquals(List.of("id"), primaryKeys),
                () -> assertTrue(jdbc.sql.contains("information_schema.KEY_COLUMN_USAGE")),
                () -> assertEquals(List.of("app", "users"), jdbc.parameters));
    }

    @Test
    void keepsJdbcCompositePrimaryKeysInKeySequenceOrder() throws Exception {
        PrimaryKeyJdbc jdbc = new PrimaryKeyJdbc(
                List.of(new PrimaryKey("second_id", 2), new PrimaryKey("first_id", 1)), List.of());

        List<String> primaryKeys = DatabaseService.readPrimaryKeys(
                jdbc.connection(), ConnectType.POSTGRESQL, null, "public", "items");

        assertAll(
                () -> assertEquals(List.of("first_id", "second_id"), primaryKeys),
                () -> assertEquals("", jdbc.sql),
                () -> assertTrue(jdbc.parameters.isEmpty()));
    }

    @Test
    void resolvesCaseDifferencesOnlyWhenTheColumnIsUnambiguous() {
        assertAll(
                () -> assertEquals(List.of("ID"), DatabaseService.resolvePrimaryKeyColumnNames(
                        List.of("id"), List.of("ID", "name"))),
                () -> assertEquals(List.of("Id"), DatabaseService.resolvePrimaryKeyColumnNames(
                        List.of("Id"), List.of("Id", "id"))),
                () -> assertEquals(List.of(), DatabaseService.resolvePrimaryKeyColumnNames(
                        List.of("ID"), List.of("Id", "id"))));
    }

    private record PrimaryKey(String columnName, int keySequence) {
    }

    private static final class PrimaryKeyJdbc {
        private final List<PrimaryKey> metadataRows;
        private final List<String> fallbackRows;
        private final List<String> parameters = new ArrayList<>();
        private String sql = "";

        private PrimaryKeyJdbc(List<?> metadataRows, List<String> fallbackRows) {
            this.metadataRows = metadataRows.stream()
                    .map(value -> value instanceof PrimaryKey primaryKey
                            ? primaryKey
                            : new PrimaryKey(value.toString(), 1))
                    .toList();
            this.fallbackRows = fallbackRows;
        }

        private Connection connection() {
            DatabaseMetaData metadata = (DatabaseMetaData) Proxy.newProxyInstance(
                    DatabaseMetaData.class.getClassLoader(), new Class<?>[]{DatabaseMetaData.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getPrimaryKeys")) return metadataResultSet();
                        return defaultValue(method.getReturnType());
                    });

            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMetaData" -> metadata;
                        case "prepareStatement" -> {
                            sql = (String) args[0];
                            yield preparedStatement();
                        }
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private PreparedStatement preparedStatement() {
            return (PreparedStatement) Proxy.newProxyInstance(
                    PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "setString" -> {
                            int parameterIndex = (int) args[0];
                            while (parameters.size() < parameterIndex) parameters.add(null);
                            parameters.set(parameterIndex - 1, (String) args[1]);
                            yield null;
                        }
                        case "executeQuery" -> fallbackResultSet();
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet metadataResultSet() {
            int[] index = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> ++index[0] < metadataRows.size();
                        case "getString" -> metadataRows.get(index[0]).columnName();
                        case "getInt" -> metadataRows.get(index[0]).keySequence();
                        case "wasNull" -> false;
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private ResultSet fallbackResultSet() {
            int[] index = {-1};
            return (ResultSet) Proxy.newProxyInstance(
                    ResultSet.class.getClassLoader(), new Class<?>[]{ResultSet.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "next" -> ++index[0] < fallbackRows.size();
                        case "getString" -> fallbackRows.get(index[0]);
                        default -> defaultValue(method.getReturnType());
                    });
        }

        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive()) return null;
            if (returnType == boolean.class) return false;
            if (returnType == byte.class) return (byte) 0;
            if (returnType == short.class) return (short) 0;
            if (returnType == int.class) return 0;
            if (returnType == long.class) return 0L;
            if (returnType == float.class) return 0F;
            if (returnType == double.class) return 0D;
            if (returnType == char.class) return '\0';
            return null;
        }
    }
}

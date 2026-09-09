package com.tangluobo.tomato.module.connect.view;

import com.tangluobo.tomato.module.connect.ConnectType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryResultEditPlanTest {

    @Test
    void completePrimaryKeyMakesReturnedSourceColumnsEditableAndRowsDeletable() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("id", "name"),
                List.of("id", "name"),
                List.of("users", "users"),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertTrue(plan.isEditable()),
                () -> assertTrue(plan.canDelete()),
                () -> assertFalse(plan.usesOriginalRowMatch()),
                () -> assertEquals(Set.of(0, 1), plan.editableColumnIndexes()),
                () -> assertEquals(List.of("id", "name"), plan.databaseColumnNames()),
                () -> assertEquals(List.of("id"), plan.primaryKeyColumns()));
    }

    @Test
    void jdbcSourceNamesKeepAliasedColumnsEditable() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.MYSQL,
                "users",
                List.of("user_id", "display_name"),
                List.of("ID", "Name"),
                List.of("USERS", "users"),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertTrue(plan.isEditable()),
                () -> assertTrue(plan.canDelete()),
                () -> assertEquals(Set.of(0, 1), plan.editableColumnIndexes()),
                () -> assertEquals(List.of("id", "name"), plan.databaseColumnNames()),
                () -> assertEquals(List.of("id"), plan.primaryKeyColumns()));
    }

    @Test
    void expressionAndForeignTableColumnsStayReadOnly() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("id", "upper_name", "role_name"),
                List.of("id", "", "name"),
                List.of("users", "", "roles"),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertTrue(plan.isEditable()),
                () -> assertTrue(plan.canDelete()),
                () -> assertEquals(Set.of(0), plan.editableColumnIndexes()),
                () -> assertEquals("id", plan.databaseColumnNames().get(0)),
                () -> assertFalse(plan.databaseColumnNames().get(1).equalsIgnoreCase("name")),
                () -> assertFalse(plan.databaseColumnNames().get(2).equalsIgnoreCase("name")));
    }

    @Test
    void unmappedExpressionAliasCannotImpersonateThePrimaryKey() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("id", "name"),
                List.of("", "name"),
                List.of("users", "users"),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertFalse(plan.isEditable()),
                () -> assertFalse(plan.canDelete()),
                () -> assertEquals(Set.of(), plan.editableColumnIndexes()),
                () -> assertEquals(List.of(), plan.primaryKeyColumns()));
    }

    @Test
    void onlyTheFirstProjectionOfTheSameSourceColumnIsEditable() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.MYSQL,
                "users",
                List.of("id", "duplicate_id", "name"),
                List.of("id", "id", "name"),
                List.of("users", "users", "users"),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertTrue(plan.isEditable()),
                () -> assertTrue(plan.canDelete()),
                () -> assertEquals(Set.of(0, 2), plan.editableColumnIndexes()),
                () -> assertEquals(List.of("id"), plan.primaryKeyColumns()));
    }

    @Test
    void incompleteCompositePrimaryKeyKeepsTheResultReadOnly() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "memberships",
                List.of("tenant_id", "display_name"),
                List.of("tenant_id", "name"),
                List.of("memberships", "memberships"),
                List.of(
                        column("tenant_id", true),
                        column("user_id", true),
                        column("name", false)));

        assertAll(
                () -> assertFalse(plan.isEditable()),
                () -> assertFalse(plan.canDelete()),
                () -> assertFalse(plan.usesOriginalRowMatch()),
                () -> assertEquals(Set.of(), plan.editableColumnIndexes()),
                () -> assertEquals(List.of(), plan.primaryKeyColumns()));
    }

    @Test
    void mysqlWithoutPrimaryKeyCanEditOnlyACompleteOriginalRow() {
        List<Map<String, String>> tableColumns = List.of(
                column("code", false),
                column("name", false));

        QueryResultEditPlan fullRow = QueryResultEditPlan.create(
                ConnectType.MYSQL,
                "labels",
                List.of("name", "code"),
                List.of("name", "code"),
                List.of("labels", "labels"),
                tableColumns);
        QueryResultEditPlan partialRow = QueryResultEditPlan.create(
                ConnectType.MYSQL,
                "labels",
                List.of("name"),
                List.of("name"),
                List.of("labels"),
                tableColumns);

        assertAll(
                () -> assertTrue(fullRow.isEditable()),
                () -> assertFalse(fullRow.canDelete()),
                () -> assertTrue(fullRow.usesOriginalRowMatch()),
                () -> assertEquals(Set.of(0, 1), fullRow.editableColumnIndexes()),
                () -> assertFalse(partialRow.isEditable()),
                () -> assertFalse(partialRow.canDelete()),
                () -> assertFalse(partialRow.usesOriginalRowMatch()));
    }

    @Test
    void databasesWithoutOriginalRowFallbackRequireAPrimaryKey() {
        List<Map<String, String>> tableColumns = List.of(
                column("code", false),
                column("name", false));

        QueryResultEditPlan postgres = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "labels",
                List.of("code", "name"),
                List.of("code", "name"),
                List.of("labels", "labels"),
                tableColumns);
        QueryResultEditPlan oracle = QueryResultEditPlan.create(
                ConnectType.ORACLE,
                "labels",
                List.of("code", "name"),
                List.of("code", "name"),
                List.of("labels", "labels"),
                tableColumns);

        assertAll(
                () -> assertFalse(postgres.isEditable()),
                () -> assertFalse(postgres.canDelete()),
                () -> assertFalse(oracle.isEditable()),
                () -> assertFalse(oracle.canDelete()));
    }

    @Test
    void allEmptyJdbcOriginsCannotMakeExpressionLabelsEditable() {
        QueryResultEditPlan plan = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("ID", "NAME"),
                List.of("", ""),
                List.of("", ""),
                List.of(column("id", true), column("name", false)));

        assertAll(
                () -> assertFalse(plan.isEditable()),
                () -> assertFalse(plan.canDelete()),
                () -> assertEquals(Set.of(), plan.editableColumnIndexes()),
                () -> assertEquals(List.of(), plan.primaryKeyColumns()));
    }

    @Test
    void caseSensitiveQuotedColumnsRequireAnExactOrUnambiguousSourceName() {
        List<Map<String, String>> tableColumns = List.of(
                column("id", true),
                column("Name", false),
                column("name", false));

        QueryResultEditPlan exact = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("id", "upper_case_name", "lower_case_name"),
                List.of("id", "Name", "name"),
                List.of("users", "users", "users"),
                tableColumns);
        QueryResultEditPlan ambiguous = QueryResultEditPlan.create(
                ConnectType.POSTGRESQL,
                "users",
                List.of("id", "ambiguous_name"),
                List.of("id", "NAME"),
                List.of("users", "users"),
                tableColumns);

        assertAll(
                () -> assertEquals(List.of("id", "Name", "name"), exact.databaseColumnNames()),
                () -> assertEquals(Set.of(0, 1, 2), exact.editableColumnIndexes()),
                () -> assertTrue(exact.canDelete()),
                () -> assertEquals(Set.of(0), ambiguous.editableColumnIndexes()),
                () -> assertEquals("id", ambiguous.databaseColumnNames().get(0)),
                () -> assertFalse(ambiguous.databaseColumnNames().get(1).equals("Name")),
                () -> assertFalse(ambiguous.databaseColumnNames().get(1).equals("name")));
    }

    private static Map<String, String> column(String name, boolean primaryKey) {
        Map<String, String> column = new LinkedHashMap<>();
        column.put("字段名", name);
        column.put("主键", primaryKey ? "是" : "否");
        return column;
    }
}

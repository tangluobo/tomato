package com.tangluobo.tomato.module.connect.view;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlCompletionEngineTest {

    private static final List<SqlCompletionEngine.Candidate> FLAT = List.of(
            new SqlCompletionEngine.Candidate("SELECT", "关键字"),
            new SqlCompletionEngine.Candidate("shop", "数据库"),
            new SqlCompletionEngine.Candidate("users", "表"),
            new SqlCompletionEngine.Candidate("username", "字段"));

    private static final Map<String, List<String>> COLUMNS = Map.of(
            "users", List.of("id", "username", "created_at"),
            "user_roles", List.of("user_id", "role_id"));

    @Test
    void tableDotSuggestsOnlyThatTablesColumns() {
        SqlCompletionEngine.Completion completion = complete("select users.");

        assertEquals(13, completion.replacementStart());
        assertEquals(List.of("created_at", "id", "username"), texts(completion));
        assertTrue(completion.candidates().stream().allMatch(item -> "字段".equals(item.kind())));
    }

    @Test
    void textAfterTableDotFiltersColumnsAsUserTypes() {
        SqlCompletionEngine.Completion completion = complete("select users.cr");

        assertEquals(13, completion.replacementStart());
        assertEquals(List.of("created_at"), texts(completion));
    }

    @Test
    void databaseDotSuggestsTablesAndFiltersThem() {
        SqlCompletionEngine.Completion allTables = complete("select * from shop.");
        SqlCompletionEngine.Completion filtered = complete("select * from shop.user_");

        assertEquals(List.of("user_roles", "users"), texts(allTables));
        assertEquals(List.of("user_roles"), texts(filtered));
        assertTrue(allTables.candidates().stream().allMatch(item -> "表".equals(item.kind())));
    }

    @Test
    void databaseTableDotSuggestsColumnsAndKeepsQualifierDuringReplacement() {
        String sql = "select shop.users.us";
        SqlCompletionEngine.Completion completion = complete(sql);

        assertEquals(sql.lastIndexOf('.') + 1, completion.replacementStart());
        assertEquals(List.of("username"), texts(completion));
    }

    @Test
    void schemaTableDotWorksCaseInsensitively() {
        SqlCompletionEngine.Completion completion = SqlCompletionEngine.complete(
                "select PUBLIC.USERS.", "select PUBLIC.USERS.".length(),
                FLAT, "shop", Set.of("shop", "public"), COLUMNS);

        assertEquals(List.of("created_at", "id", "username"), texts(completion));
    }

    @Test
    void unrelatedDatabaseDoesNotReuseCurrentDatabaseMetadata() {
        SqlCompletionEngine.Completion completion = complete("select other.users.");

        assertTrue(completion.candidates().isEmpty());
    }

    @Test
    void unqualifiedPrefixKeepsExistingFlatCompletion() {
        SqlCompletionEngine.Completion completion = complete("sel");

        assertEquals(0, completion.replacementStart());
        assertEquals(List.of("SELECT"), texts(completion));
    }

    private static SqlCompletionEngine.Completion complete(String sql) {
        return SqlCompletionEngine.complete(
                sql, sql.length(), FLAT, "shop", Set.of("shop", "public"), COLUMNS);
    }

    private static List<String> texts(SqlCompletionEngine.Completion completion) {
        return completion.candidates().stream().map(SqlCompletionEngine.Candidate::text).toList();
    }
}

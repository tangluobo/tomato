package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SqlSplitterTest {

    @Test
    void extractsOnlyAnUnambiguousSingleSourceTable() {
        assertEquals("users", SqlSplitter.extractTableName(
                "select id, name from users where enabled = 1"));
        assertEquals("public.users", SqlSplitter.extractTableName(
                "select id from \"public\".\"users\" u order by id"));
    }

    @Test
    void rejectsMultipleSourceQueriesForSafeResultEditing() {
        assertNull(SqlSplitter.extractTableName(
                "select u.id, r.name from users u join roles r on r.id = u.role_id"));
        assertNull(SqlSplitter.extractTableName(
                "select u.id from users u, roles r where r.id = u.role_id"));
    }

    @Test
    void ignoresJoinAndCommaTextOutsideTheTopLevelFromList() {
        assertEquals("users", SqlSplitter.extractTableName(
                "select id from users where note = 'join, later'"));
    }
}

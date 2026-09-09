package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
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

    @Test
    void extractsSingleSourceWithCommentsAliasesAndQuotedQualifiers() {
        assertAll(
                () -> assertEquals("users", SqlSplitter.extractTableName(
                        "-- result remains editable\nselect id, name from users as u where u.id = 1")),
                () -> assertEquals("app.users", SqlSplitter.extractTableName(
                        "select id from `app`.`users` u")),
                () -> assertEquals("sales.orders", SqlSplitter.extractTableName(
                        "select id from [sales].[orders] o")));
    }

    @Test
    void allowsNestedQueriesThatDoNotChangeTheSingleTopLevelSource() {
        assertAll(
                () -> assertEquals("users", SqlSplitter.extractTableName(
                        "select id, (select max(id) from audit_log) last_audit_id from users")),
                () -> assertEquals("users", SqlSplitter.extractTableName(
                        "select id from users where role_id in (select id from roles)")));
    }

    @Test
    void rejectsDerivedTablesForSafeResultEditing() {
        assertAll(
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from (select id from users) u")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from (values (1), (2)) v(id)")));
    }

    @Test
    void rejectsSetOperationResultsForSafeResultEditing() {
        assertAll(
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from active_users union select id from archived_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from active_users union all select id from archived_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from users except select id from blocked_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from users intersect select id from licensed_users")));
    }

    @Test
    void rejectsSetOperationsThatAppearAfterTopLevelResultClauses() {
        assertAll(
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from active_users where enabled = 1 "
                                + "union select id from archived_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select team_id from active_users group by team_id "
                                + "except select team_id from blocked_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from active_users order by id "
                                + "intersect select id from licensed_users")),
                () -> assertNull(SqlSplitter.extractTableName(
                        "select id from active_users limit 10 "
                                + "union all select id from archived_users")));
    }
}

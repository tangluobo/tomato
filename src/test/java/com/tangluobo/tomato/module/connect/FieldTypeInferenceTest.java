package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class FieldTypeInferenceTest {

    @Test
    void mysqlRecognizesIdentifierAndTemporalNames() {
        assertAll(
                () -> assertSuggestion(ConnectType.MYSQL, "id", "BIGINT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "user_id", "BIGINT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "userId", "BIGINT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "birthday", "DATE", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "start_time", "TIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "created_at", "DATETIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "updatedDateTime", "DATETIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "log_datatime", "DATETIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "title", "VARCHAR", "255", ""));
    }

    @Test
    void recognizesCommonChineseFieldNames() {
        assertAll(
                () -> assertSuggestion(ConnectType.MYSQL, "用户编号", "BIGINT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "出生日期", "DATE", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "开始时间", "TIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "创建时间", "DATETIME", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "日期时间", "DATETIME", "", ""));
    }

    @Test
    void avoidsPartialIdMatches() {
        assertAll(
                () -> assertSuggestion(ConnectType.MYSQL, "identity", "VARCHAR", "255", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "idea", "VARCHAR", "255", ""));
    }

    @Test
    void usesPostgresqlNativeTypes() {
        assertAll(
                () -> assertSuggestion(ConnectType.POSTGRESQL, "account_id", "bigint", "", ""),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "event_date", "date", "", ""),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "event_time", "time", "", ""),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "event_timestamp", "timestamp", "", ""),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "request_uuid", "uuid", "", ""),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "display_name", "character varying", "255", ""));
    }

    @Test
    void usesOracleCompatibleTypes() {
        assertAll(
                () -> assertSuggestion(ConnectType.ORACLE, "order_id", "NUMBER", "19", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "business_date", "DATE", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "start_time", "TIMESTAMP", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "created_at", "TIMESTAMP", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "name", "VARCHAR2", "255", ""));
    }

    @Test
    void usesHiveNativeTypes() {
        assertAll(
                () -> assertSuggestion(ConnectType.HIVE, "event_id", "BIGINT", "", ""),
                () -> assertSuggestion(ConnectType.HIVE, "business_date", "DATE", "", ""),
                () -> assertSuggestion(ConnectType.HIVE, "created_at", "TIMESTAMP", "", ""),
                () -> assertSuggestion(ConnectType.HIVE, "total_amount", "DECIMAL", "18", "2"),
                () -> assertSuggestion(ConnectType.HIVE, "payload_json", "STRING", "", ""),
                () -> assertSuggestion(ConnectType.HIVE, "display_name", "STRING", "", ""));
    }

    @Test
    void recognizesOtherCommonFieldConventions() {
        assertAll(
                () -> assertSuggestion(ConnectType.MYSQL, "is_enabled", "BOOLEAN", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "item_count", "INT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "total_amount", "DECIMAL", "18", "2"),
                () -> assertSuggestion(ConnectType.POSTGRESQL, "description", "text", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "payload_blob", "BLOB", "", ""));
    }

    @Test
    void fallsBackWhenVersionDoesNotSupportNativeJson() {
        assertAll(
                () -> assertSuggestion(ConnectType.MYSQL, "payload_json", List.of("VARCHAR", "LONGTEXT"),
                        "LONGTEXT", "", ""),
                () -> assertSuggestion(ConnectType.MYSQL, "payload_json", List.of("VARCHAR", "JSON"),
                        "JSON", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "payload_json", List.of("VARCHAR2", "CLOB"),
                        "CLOB", "", ""),
                () -> assertSuggestion(ConnectType.ORACLE, "payload_json", List.of("VARCHAR2", "JSON"),
                        "JSON", "", ""));
    }

    @Test
    void ignoresBlankNamesAndNonRelationalConnections() {
        assertAll(
                () -> assertNull(FieldTypeInference.infer(ConnectType.MYSQL, "  ")),
                () -> assertNull(FieldTypeInference.infer(ConnectType.SSH, "id")));
    }

    private static void assertSuggestion(ConnectType type, String fieldName,
                                         String expectedType, String expectedLength, String expectedDecimals) {
        assertSuggestion(type, fieldName, List.of(), expectedType, expectedLength, expectedDecimals);
    }

    private static void assertSuggestion(ConnectType type, String fieldName, List<String> availableTypes,
                                         String expectedType, String expectedLength, String expectedDecimals) {
        FieldTypeInference.Suggestion actual = FieldTypeInference.infer(type, fieldName, availableTypes);
        assertEquals(new FieldTypeInference.Suggestion(expectedType, expectedLength, expectedDecimals), actual);
    }
}

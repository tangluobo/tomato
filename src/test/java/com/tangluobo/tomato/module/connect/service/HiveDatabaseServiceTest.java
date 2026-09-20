package com.tangluobo.tomato.module.connect.service;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiveDatabaseServiceTest {

    @Test
    void preservesHiveServer2ParametersWhenSwitchingDatabases() {
        assertAll(
                () -> assertEquals("analytics;transportMode=http;httpPath=cliservice",
                        DatabaseService.hiveDatabaseWithConfiguredParameters(
                                "default;transportMode=http;httpPath=cliservice", "analytics")),
                () -> assertEquals("warehouse?hiveconf:hive.execution.engine=tez#hivevar:env=prod",
                        DatabaseService.hiveDatabaseWithConfiguredParameters(
                                "default?hiveconf:hive.execution.engine=tez#hivevar:env=prod", "warehouse")),
                () -> assertEquals("default;transportMode=http",
                        DatabaseService.hiveDatabaseWithConfiguredParameters(
                                ";transportMode=http", "")));
    }

    @Test
    void generatesHiveCreateAndAlterStatements() {
        ConnectionConfig config = hiveConfig();
        Map<String, String> id = column("event`id", "BIGINT", "", "", "identifier");
        id.put("主键", "是");
        Map<String, String> amount = column("amount", "DECIMAL", "18", "2", "money's value");

        String create = DatabaseService.generateCreateTableSql(
                config, "analytics", "daily events", List.of(id, amount), Map.of(), "daily's events");
        ObservableList<String> added = row("payload", "STRING", "", "", "json's body");
        List<String> titles = List.of("字段名", "类型", "长度", "小数点", "注释");
        ObservableList<String> oldAmount = row("amount", "DECIMAL", "18", "2", "money's value");
        ObservableList<String> renamedAmount = row("total_amount", "DECIMAL", "20", "4", "total");

        assertAll(
                () -> assertEquals("CREATE TABLE `analytics`.`daily events` (\n"
                                + "    `event``id` BIGINT COMMENT 'identifier',\n"
                                + "    `amount` DECIMAL(18,2) COMMENT 'money''s value'\n"
                                + ") COMMENT 'daily''s events';", create),
                () -> assertEquals("ALTER TABLE `analytics`.`daily events` ADD COLUMNS "
                                + "(`payload` STRING COMMENT 'json''s body')",
                        DatabaseService.generateAddColumnSql(
                                config, "analytics", null, "daily events", titles, added)),
                () -> assertEquals("ALTER TABLE `analytics`.`daily events` SET TBLPROPERTIES "
                                + "('comment'='daily''s events')",
                        DatabaseService.generateUpdateTableCommentSql(
                                config, "analytics", "daily events", "daily's events")),
                () -> assertEquals(List.of("ALTER TABLE `analytics`.`daily events` CHANGE COLUMN "
                                + "`amount` `total_amount` DECIMAL(20,4) COMMENT 'total'"),
                        DatabaseService.generateModifyColumnSql(config, "analytics", null,
                                "daily events", titles, oldAmount, renamedAmount)));

        UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class,
                () -> DatabaseService.generateDropColumnSql(
                        config, "analytics", null, "daily events", "payload"));
        assertTrue(error.getMessage().contains("REPLACE COLUMNS"));
    }

    private static ConnectionConfig hiveConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectType.HIVE);
        return config;
    }

    private static Map<String, String> column(String name, String type, String length,
                                               String decimals, String comment) {
        Map<String, String> column = new LinkedHashMap<>();
        column.put("字段名", name);
        column.put("类型", type);
        column.put("长度", length);
        column.put("小数点", decimals);
        column.put("注释", comment);
        return column;
    }

    private static ObservableList<String> row(String name, String type, String length,
                                               String decimals, String comment) {
        return FXCollections.observableArrayList(name, type, length, decimals, comment);
    }
}

package com.tangluobo.tomato.module.connect.handler;

import javafx.scene.control.Tab;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AbstractDbHandlerConnectionTabTest {

    @Test
    void removesEveryDatabaseObjectTabOwnedByTheConnection() {
        String targetConnection = "conn";

        Tab objects = taggedTab("对象-db1", "objects_conn_db1", targetConnection);
        Tab tableData = taggedTab("数据-db1", "conn_db1_public_student", targetConnection);
        Tab tableStructure = taggedTab("结构-db2", "struct_conn_db2_orders", targetConnection);
        Tab newTable = taggedTab("新建表-db2", "newtable_conn_db2", targetConnection);
        // 模拟数据库节点已从连接树移除后遗留的对象标签。
        Tab orphanObject = taggedTab("遗留对象", "objects_conn_deleted_db", targetConnection);

        Tab otherConnection = taggedTab("其他连接", "objects_conn_child_db1", "conn_child");
        Tab savedQuery = plainTab("查询", "query_conn_db1_folder_query1");
        Tab newQuery = plainTab("未保存查询", "query_new_123");
        Tab settings = plainTab("设置", "__settings__");
        Tab unowned = plainTab("无归属", null);

        List<Tab> tabs = new ArrayList<>(List.of(
                objects, tableData, tableStructure, newTable, orphanObject,
                otherConnection, savedQuery, newQuery, settings, unowned));

        int removed = AbstractDbHandler.removeDatabaseObjectTabsForConnection(tabs, targetConnection);

        assertEquals(5, removed);
        assertEquals(List.of(otherConnection, savedQuery, newQuery, settings, unowned), tabs);
    }

    @Test
    void missingConnectionIdDoesNotRemoveAnything() {
        Tab objectTab = taggedTab("对象", "objects_conn_db", "conn");
        List<Tab> tabs = new ArrayList<>(List.of(objectTab));

        assertEquals(0, AbstractDbHandler.removeDatabaseObjectTabsForConnection(tabs, null));
        assertEquals(List.of(objectTab), tabs);
    }

    private static Tab taggedTab(String title, Object userData, String connectionId) {
        Tab tab = plainTab(title, userData);
        AbstractDbHandler.tagDatabaseObjectTab(tab, connectionId);
        return tab;
    }

    private static Tab plainTab(String title, Object userData) {
        Tab tab = new Tab(title);
        tab.setUserData(userData);
        return tab;
    }
}

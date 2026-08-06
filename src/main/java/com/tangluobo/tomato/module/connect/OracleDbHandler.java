package com.tangluobo.tomato.module.connect;

import javafx.scene.control.TreeItem;

/**
 * Oracle 数据库处理器。
 * - openDatabase: 直接加载数据库下的表/视图/函数/查询/备份 5 个文件夹（无 schema 层级）
 * - updateHostIcon: 使用通用图标更新逻辑
 */
public class OracleDbHandler extends AbstractDbHandler {

    public OracleDbHandler(ConnectModule module) {
        super(module);
    }

    @Override
    public ConnectType getConnectType() {
        return ConnectType.ORACLE;
    }

    /**
     * 打开 Oracle 数据库节点：直接加载 5 个文件夹（表/视图/函数/查询/备份）
     */
    @Override
    public void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        openDatabaseWithFolders(dbItem, data);
    }

    /**
     * Oracle 主机图标更新：使用通用逻辑
     */
    @Override
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        module.updateHostIconGeneric(hostItem, config, connected);
    }

    @Override
    public boolean supportsSchema() {
        return false;
    }
}

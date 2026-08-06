package com.tangluobo.tomato.module.connect;

import javafx.scene.control.TreeItem;

/**
 * MySQL 数据库处理器。
 * - openDatabase: 直接加载数据库下的表/视图/函数/查询/备份 5 个文件夹（无 schema 层级）
 * - updateHostIcon: MySQL 专用图标更新逻辑
 */
public class MysqlDbHandler extends AbstractDbHandler {

    public MysqlDbHandler(ConnectModule module) {
        super(module);
    }

    @Override
    public ConnectType getConnectType() {
        return ConnectType.MYSQL;
    }

    /**
     * 打开 MySQL 数据库节点：直接加载 5 个文件夹（表/视图/函数/查询/备份）
     */
    @Override
    public void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data) {
        openDatabaseWithFolders(dbItem, data);
    }

    /**
     * MySQL 主机图标更新：使用专用逻辑
     */
    @Override
    public void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected) {
        module.updateMysqlHostIcon(hostItem, config);
    }

    @Override
    public boolean supportsSchema() {
        return false;
    }
}

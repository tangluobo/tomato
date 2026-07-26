package com.tangluobo.tomato.module.connect;

/**
 * 数据库树节点的元数据，用于标识动态加载的节点类型（数据库、表、视图等）
 */
public class DatabaseNodeData {
    public enum NodeType {
        DATABASE,       // 数据库节点
        TABLES_FOLDER,  // "表"文件夹节点
        VIEWS_FOLDER,   // "视图"文件夹节点
        QUERY_FOLDER,   // "查询"文件夹节点
        TABLE,          // 表节点
        VIEW,           // 视图节点
        FUNCTION_FOLDER, BACKUP_FOLDER, QUERY           // 查询节点
    }

    private final NodeType type;
    private final String name;
    private final ConnectionConfig connectionConfig;
    private final String databaseName;
    private boolean opened;

    public DatabaseNodeData(NodeType type, String name, ConnectionConfig connectionConfig, String databaseName) {
        this.type = type;
        this.name = name;
        this.connectionConfig = connectionConfig;
        this.databaseName = databaseName;
        this.opened = false;
    }

    public NodeType getType() { return type; }
    public String getName() { return name; }
    public ConnectionConfig getConnectionConfig() { return connectionConfig; }
    public String getDatabaseName() { return databaseName; }
    public boolean isOpened() { return opened; }
    public void setOpened(boolean opened) { this.opened = opened; }
}

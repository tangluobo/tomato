package com.tangluobo.tomato.module.connect;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.TreeItem;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.util.List;

/**
 * 数据库连接处理器抽象基类。
 * 封装 MySQL/PostgreSQL/Oracle 等关系型数据库在连接树中的公共行为，
 * 差异点（数据库节点展开方式、主机图标更新、模式加载等）由子类实现。
 *
 * 设计说明：
 * - 公共逻辑（加载数据库列表、表/视图文件夹、新建表/查询、删除节点等）放在此基类
 * - 抽象方法定义差异点，由 MysqlDbHandler/PostgresDbHandler/OracleDbHandler 实现
 * - 通过持有的 ConnectModule 引用访问共享 UI 状态（树、tab 面板、图标等）
 * - 实现 ConnectHandler 接口，使 handleConnect 也可通过统一分发机制调用
 */
public abstract class AbstractDbHandler implements ConnectHandler {

    /** 关联的连接模块，提供共享 UI 状态与公共回调 */
    protected final ConnectModule module;

    protected AbstractDbHandler(ConnectModule module) {
        this.module = module;
    }

    /**
     * 此处理器对应的数据库连接类型
     */
    public abstract ConnectType getConnectType();

    // ==================== 抽象方法：差异点 ====================

    /**
     * 打开数据库节点：展开下级目录结构。
     * PostgreSQL 实现为加载模式(schema)节点；MySQL/Oracle 实现为直接加载表/视图/函数/查询/备份文件夹。
     */
    public abstract void openDatabase(TreeItem<String> dbItem, DatabaseNodeData data);

    /**
     * 更新主机节点图标（根据连接状态）。
     * MySQL 有特殊的图标更新逻辑；其他数据库使用通用逻辑。
     */
    public abstract void updateHostIcon(TreeItem<String> hostItem, ConnectionConfig config, boolean connected);

    /**
     * 是否支持模式(schema)层级。
     * PostgreSQL 返回 true（数据库→模式→表）；MySQL/Oracle 返回 false。
     */
    public boolean supportsSchema() {
        return false;
    }

    /**
     * 双击模式节点处理。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void handleSchemaDoubleClick(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作：MySQL/Oracle 无 schema 层级
    }

    /**
     * 刷新模式节点。仅 PostgreSQL 实现；其他数据库默认空操作。
     */
    public void refreshSchema(TreeItem<String> schemaItem, DatabaseNodeData data) {
        // 默认无操作
    }

    // ==================== 公共方法 ====================

    /**
     * 双击主机节点：加载数据库列表。
     * MySQL/PostgreSQL/Oracle 逻辑一致：密码输入 → 后台调 DatabaseService.getDatabases → 填充数据库节点。
     */
    public void handleHostDoubleClick(TreeItem<String> hostItem, ConnectionConfig config) {
        module.doHandleDbHostDoubleClick(hostItem, config, this);
    }

    /**
     * 关闭主机连接：释放 JDBC 连接资源。
     * 关系型数据库统一调用 DatabaseService.closeConnection。
     */
    public void closeConnection(ConnectionConfig config) {
        try {
            DatabaseService.closeConnection(config.getId());
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断给定的连接配置是否由本处理器处理
     */
    public boolean handles(ConnectionConfig config) {
        return config.getType() == getConnectType();
    }

    // ==================== ConnectHandler 接口实现 ====================

    @Override
    public boolean supports(ConnectType type) {
        return type == getConnectType();
    }

    /**
     * 执行连接：找到主机树节点并触发双击连接流程
     */
    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            handleHostDoubleClick(hostItem, config);
        }
    }
}

package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.DatabaseNodeData;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.TreeItem;

/**
 * 连接处理器接口：每种连接类型（MySQL/PG/Redis/SSH/RDP/Aliyun 等）实现一个子类，
 * 负责 handleConnect 的具体逻辑。ConnectModule 通过工厂方法分发。
 */
public interface ConnectHandler {

    /**
     * 此处理器支持的连接类型
     */
    boolean supports(ConnectType type);

    /**
     * 执行连接逻辑
     * @param module 关联的连接模块，提供共享 UI 状态
     * @param config 连接配置
     */
    void handleConnect(ConnectModule module, ConnectionConfig config);

    /**
     * 双击主机节点：加载主机下的资源列表（数据库/主题/云产品等）。
     * 默认实现委托给 handleConnect（会通过 ID 重新查找节点）；
     * 已持有 hostItem 的处理器应重写此方法以避免冗余查找。
     *
     * @param module   关联的连接模块
     * @param hostItem 主机树节点
     * @param config   连接配置
     */
    default void handleHostDoubleClick(ConnectModule module, TreeItem<String> hostItem, ConnectionConfig config) {
        handleConnect(module, config);
    }

    /**
     * 为节点构建右键菜单项。
     * 默认空实现，由各 handler 按需重写（添加该连接类型特有或共用的菜单项到 contextMenu）。
     *
     * @param module      关联的连接模块
     * @param contextMenu 右键菜单（已清空，由本方法填充）
     * @param item        触发右键的树节点
     * @param data        节点关联数据
     */
    default void populateNodeContextMenu(ConnectModule module, ContextMenu contextMenu, TreeItem<String> item, DatabaseNodeData data) {
        // 默认空：未实现菜单的 handler 不添加任何项
    }
}

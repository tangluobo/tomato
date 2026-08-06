package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
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
}

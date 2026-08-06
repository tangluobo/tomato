package com.tangluobo.tomato.module.connect;

import javafx.scene.control.TreeItem;

/**
 * RocketMQ 连接处理器
 */
public class RocketmqConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.ROCKETMQ;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            module.handleRocketmqHostDoubleClick(hostItem, config);
        }
    }
}

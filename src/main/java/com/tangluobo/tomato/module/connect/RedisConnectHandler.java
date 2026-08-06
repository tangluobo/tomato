package com.tangluobo.tomato.module.connect;

import javafx.scene.control.TreeItem;

/**
 * Redis 连接处理器
 */
public class RedisConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.REDIS;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            module.handleRedisHostDoubleClick(hostItem, config);
        }
    }
}

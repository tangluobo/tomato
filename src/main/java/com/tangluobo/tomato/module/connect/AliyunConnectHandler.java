package com.tangluobo.tomato.module.connect;

import javafx.scene.control.TreeItem;

/**
 * 阿里云连接处理器
 */
public class AliyunConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.ALIYUN;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        TreeItem<String> hostItem = module.findItemById(module.getRoot(), config.getId());
        if (hostItem != null) {
            module.handleAliyunHostDoubleClick(hostItem, config);
        }
    }
}

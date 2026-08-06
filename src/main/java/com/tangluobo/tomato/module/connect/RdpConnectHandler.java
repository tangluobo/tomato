package com.tangluobo.tomato.module.connect;

import javafx.scene.control.Tab;

/**
 * RDP 远程桌面连接处理器
 */
public class RdpConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.RDP;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        // 若已有打开的 RDP tab，直接切换选中
        for (Tab tab : module.getTerminalTabPane().getTabs()) {
            if (config.getId().equals(tab.getUserData())) {
                module.getTerminalTabPane().getSelectionModel().select(tab);
                module.showTerminalView();
                return;
            }
        }
        module.doRdpConnect(config);
    }
}

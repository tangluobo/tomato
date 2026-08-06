package com.tangluobo.tomato.module.connect;

/**
 * 本地终端连接处理器
 */
public class LocalTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.LOCAL_TERMINAL;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        module.doLocalTerminalConnect(config);
    }
}

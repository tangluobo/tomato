package com.tangluobo.tomato.module.connect;

/**
 * SSH 终端连接处理器（默认分支）
 */
public class SshTerminalConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        // SSH 终端作为默认处理器，支持所有未匹配的类型（如 SSH）
        return type == ConnectType.SSH;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        module.doSshTerminalConnect(config);
    }
}

package com.tangluobo.tomato.module.connect;

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
}

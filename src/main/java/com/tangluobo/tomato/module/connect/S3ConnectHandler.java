package com.tangluobo.tomato.module.connect;

/**
 * S3/OSS 对象存储连接处理器
 */
public class S3ConnectHandler implements ConnectHandler {

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.S3 || type == ConnectType.ALIYUN_OSS;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        module.doS3Connect(config);
    }
}

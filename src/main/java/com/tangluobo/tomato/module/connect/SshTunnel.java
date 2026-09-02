package com.tangluobo.tomato.module.connect;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.util.List;

/**
 * SSH通道（端口转发），通过SSH跳板机建立到目标主机的安全隧道
 * 使用JSch的本地端口转发功能：localPort -> SSH服务器 -> targetHost:targetPort
 */
public class SshTunnel {

    private volatile Session session;
    private volatile int forwardedLocalPort;
    private volatile boolean active = false;

    private final String sshHost;
    private final int sshPort;
    private final String sshUsername;
    private final String sshPassword;
    private final List<String> sshPrivateKeyPaths;
    private final String targetHost;
    private final int targetPort;

    /**
     * 创建SSH通道
     *
     * @param sshHost           SSH跳板机地址
     * @param sshPort           SSH跳板机端口
     * @param sshUsername       SSH用户名
     * @param sshPassword       SSH密码（可为null，也可作为密钥的passphrase）
     * @param sshPrivateKeyPaths SSH密钥路径列表（可为null）
     * @param targetHost        目标主机地址（如MySQL地址）
     * @param targetPort        目标主机端口（如MySQL端口）
     */
    public SshTunnel(String sshHost, int sshPort, String sshUsername, String sshPassword,
                     List<String> sshPrivateKeyPaths, String targetHost, int targetPort) {
        this.sshHost = sshHost;
        this.sshPort = sshPort;
        this.sshUsername = sshUsername;
        this.sshPassword = sshPassword;
        this.sshPrivateKeyPaths = sshPrivateKeyPaths;
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    /**
     * 从ConnectionConfig创建SSH通道
     */
    public static SshTunnel fromConfig(ConnectionConfig config) {
        List<String> tunnelKeyPaths = config.isSshTunnelUseKey() ? config.getSshTunnelPrivateKeyPaths() : null;
        String tunnelPassword = config.isSshTunnelUsePassword() ? config.getSshTunnelPassword() : null;
        // 仅密钥认证时，密码作为passphrase
        if (!config.isSshTunnelUsePassword() && config.isSshTunnelUseKey() && config.getSshTunnelPassword() != null) {
            tunnelPassword = config.getSshTunnelPassword();
        }

        return new SshTunnel(
            config.getSshTunnelHost(),
            config.getSshTunnelPort(),
            config.getSshTunnelUsername(),
            tunnelPassword,
            tunnelKeyPaths,
            config.getHost(),
            config.getPort()
        );
    }

    /**
     * 建立SSH连接并创建端口转发
     *
     * @return 本地转发端口号
     */
    public synchronized int connect() throws Exception {
        // 同一个对象重连前先清理旧转发，避免遗留监听端口和 JSch 线程。
        disconnect();
        JSch jsch = new JSch();

        // 密钥认证
        if (sshPrivateKeyPaths != null && !sshPrivateKeyPaths.isEmpty()) {
            for (String keyPath : sshPrivateKeyPaths) {
                if (keyPath != null && !keyPath.isEmpty()) {
                    if (sshPassword != null && !sshPassword.isEmpty()) {
                        jsch.addIdentity(keyPath, sshPassword);
                    } else {
                        jsch.addIdentity(keyPath);
                    }
                }
            }
        }

        Session newSession = jsch.getSession(sshUsername, sshHost, sshPort);

        // 无密钥时使用密码认证
        if (sshPrivateKeyPaths == null || sshPrivateKeyPaths.isEmpty()) {
            newSession.setPassword(sshPassword);
        }

        newSession.setConfig("StrictHostKeyChecking", "no");
        // 开启SSH服务端保活：每10s发送keepalive，连续3次未响应则判定连接已断开，
        // 使 session.isConnected() 能在约30s内反映出真实连接状态，避免隧道已死但isActive()仍返回true导致复用死端口无法重连。
        newSession.setServerAliveInterval(10000);
        newSession.setServerAliveCountMax(3);
        try {
            newSession.connect(30000);

            // 让 JSch 原子地分配空闲端口，避免“先探测再绑定”之间被其他进程抢占。
            int localPort = newSession.setPortForwardingL(0, targetHost, targetPort);
            session = newSession;
            forwardedLocalPort = localPort;
            active = true;
            return localPort;
        } catch (Exception e) {
            newSession.disconnect();
            throw e;
        }
    }

    /**
     * 获取本地转发端口号（连接成功后可用）
     */
    public int getForwardedLocalPort() {
        return forwardedLocalPort;
    }

    /**
     * 通道是否活跃
     */
    public boolean isActive() {
        return active && session != null && session.isConnected();
    }

    /**
     * 关闭SSH通道
     */
    public synchronized void disconnect() {
        active = false;
        Session oldSession = session;
        session = null;
        if (oldSession != null) {
            try {
                if (forwardedLocalPort > 0) {
                    oldSession.delPortForwardingL(forwardedLocalPort);
                }
            } catch (Exception ignored) {}
            oldSession.disconnect();
        }
        forwardedLocalPort = 0;
    }

}

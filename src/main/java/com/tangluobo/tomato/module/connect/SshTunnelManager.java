package com.tangluobo.tomato.module.connect;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SSH 跳板隧道管理器。
 *
 * <p>每个使用者通过 {@link #acquire(ConnectionConfig)} 获得独立租约，并在自己的
 * 生命周期结束时关闭租约。同一目标的租约共享底层隧道；释放时按“隧道实例”而不是
 * 仅按配置 key 计数，避免旧会话在隧道重建后误释放新隧道。</p>
 */
public final class SshTunnelManager {

    // key: configId + "_" + targetHost:targetPort；broker 隧道使用 configId + "_broker_" 前缀
    private static final Map<String, TunnelEntry> cache = new ConcurrentHashMap<>();

    private static TunnelFactory tunnelFactory = SshTunnelManager::createReferencedTunnel;

    private SshTunnelManager() {
    }

    private static final class TunnelEntry {
        final SshTunnel tunnel;
        int refCount;

        TunnelEntry(SshTunnel tunnel, int refCount) {
            this.tunnel = tunnel;
            this.refCount = refCount;
        }
    }

    /**
     * 一次隧道使用权。close() 幂等，且只会释放本租约实际取得的那一代隧道。
     */
    public static final class TunnelLease implements AutoCloseable {
        private final ConnectionConfig config;
        private final String targetHost;
        private final int targetPort;
        private String key;
        private TunnelEntry entry;
        private boolean closed;

        private TunnelLease(ConnectionConfig config, String targetHost, int targetPort,
                            String key, TunnelEntry entry) {
            this.config = config;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.key = key;
            this.entry = entry;
        }

        /** 无隧道时返回 -1。 */
        public int getLocalPort() {
            synchronized (SshTunnelManager.class) {
                return entry == null ? -1 : entry.tunnel.getForwardedLocalPort();
            }
        }

        /**
         * 返回当前活跃隧道端口；原隧道失效时为本租约切换到新隧道。
         * 其他仍引用旧隧道的会话稍后释放时不会影响新隧道。
         */
        public int refresh() {
            return refreshLease(this);
        }

        public boolean isClosed() {
            synchronized (SshTunnelManager.class) {
                return closed;
            }
        }

        @Override
        public void close() {
            releaseLease(this);
        }
    }

    /**
     * 建立/复用 config.host:config.port 的跳板隧道并返回独立租约。
     * 未启用引用式 SSH 隧道时返回一个本地端口为 -1 的空租约。
     */
    public static synchronized TunnelLease acquire(ConnectionConfig config) {
        return acquire(config, config.getHost(), config.getPort());
    }

    /**
     * 建立/复用指定目标的跳板隧道。用于 endpoint 与 config.host 不完全相同的服务。
     */
    public static synchronized TunnelLease acquire(ConnectionConfig config, String targetHost, int targetPort) {
        if (!usesReferencedTunnel(config)) {
            return new TunnelLease(config, targetHost, targetPort, null, null);
        }
        String key = key(config, targetHost, targetPort);
        TunnelEntry entry = acquireEntry(config, targetHost, targetPort, key);
        return new TunnelLease(config, targetHost, targetPort, key, entry);
    }

    private static synchronized int refreshLease(TunnelLease lease) {
        if (lease.closed) {
            throw new IllegalStateException("SSH 隧道租约已关闭");
        }
        if (lease.entry != null && lease.entry.tunnel.isActive()) {
            return lease.entry.tunnel.getForwardedLocalPort();
        }

        TunnelEntry oldEntry = lease.entry;
        String oldKey = lease.key;
        lease.entry = null;
        lease.key = null;
        releaseEntry(oldKey, oldEntry);

        if (!usesReferencedTunnel(lease.config)) {
            return -1;
        }

        String newKey = key(lease.config, lease.targetHost, lease.targetPort);
        TunnelEntry newEntry = acquireEntry(
                lease.config, lease.targetHost, lease.targetPort, newKey);
        lease.key = newKey;
        lease.entry = newEntry;
        return newEntry.tunnel.getForwardedLocalPort();
    }

    private static synchronized void releaseLease(TunnelLease lease) {
        if (lease.closed) {
            return;
        }
        lease.closed = true;
        TunnelEntry entry = lease.entry;
        String key = lease.key;
        lease.entry = null;
        lease.key = null;
        releaseEntry(key, entry);
    }

    private static TunnelEntry acquireEntry(ConnectionConfig config, String targetHost,
                                            int targetPort, String key) {
        TunnelEntry entry = cache.get(key);
        if (entry != null && entry.tunnel.isActive()) {
            entry.refCount++;
            return entry;
        }

        if (entry != null) {
            cache.remove(key, entry);
            disconnectQuietly(entry.tunnel);
        }

        SshTunnel tunnel = null;
        try {
            tunnel = tunnelFactory.create(config, targetHost, targetPort);
            tunnel.connect();
            TunnelEntry created = new TunnelEntry(tunnel, 1);
            cache.put(key, created);
            return created;
        } catch (Exception e) {
            if (tunnel != null) {
                disconnectQuietly(tunnel);
            }
            throw new RuntimeException("建立SSH跳板隧道失败: " + e.getMessage(), e);
        }
    }

    /** 仅减少给定实例的引用，不会碰到同 key 下已经重建的新实例。 */
    private static void releaseEntry(String key, TunnelEntry entry) {
        if (entry == null) {
            return;
        }
        if (entry.refCount > 0) {
            entry.refCount--;
        }
        if (entry.refCount == 0) {
            if (key != null) {
                cache.remove(key, entry);
            }
            disconnectQuietly(entry.tunnel);
        }
    }

    /**
     * 查看已建立的活跃隧道本地端口（不取得引用）。
     * 仅供生命周期由其他长连接保证的短操作使用。
     */
    public static synchronized int peek(ConnectionConfig config) {
        if (!usesReferencedTunnel(config)) {
            return -1;
        }
        TunnelEntry entry = cache.get(key(config, config.getHost(), config.getPort()));
        return entry != null && entry.tunnel.isActive()
                ? entry.tunnel.getForwardedLocalPort() : -1;
    }

    /**
     * 建立/复用 RocketMQ broker 跳板隧道（由 closeBrokerTunnels 统一关闭）。
     */
    public static synchronized int ensureBrokerTunnel(ConnectionConfig config, String brokerHost, int brokerPort) {
        if (!usesReferencedTunnel(config)) {
            return -1;
        }
        String key = config.getId() + "_broker_" + brokerHost + ":" + brokerPort;
        TunnelEntry entry = cache.get(key);
        if (entry != null && entry.tunnel.isActive()) {
            return entry.tunnel.getForwardedLocalPort();
        }
        if (entry != null) {
            cache.remove(key, entry);
            disconnectQuietly(entry.tunnel);
        }
        SshTunnel tunnel = null;
        try {
            tunnel = tunnelFactory.create(config, brokerHost, brokerPort);
            int localPort = tunnel.connect();
            cache.put(key, new TunnelEntry(tunnel, 0));
            return localPort;
        } catch (Exception e) {
            if (tunnel != null) {
                disconnectQuietly(tunnel);
            }
            throw new RuntimeException("建立broker跳板隧道失败(" + brokerHost + ":" + brokerPort + "): "
                    + e.getMessage(), e);
        }
    }

    /** 关闭指定配置的所有 RocketMQ broker 跳板隧道。 */
    public static synchronized void closeBrokerTunnels(ConnectionConfig config) {
        if (config == null || config.getId() == null) {
            return;
        }
        String prefix = config.getId() + "_broker_";
        cache.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(prefix)) {
                disconnectQuietly(e.getValue().tunnel);
                return true;
            }
            return false;
        });
    }

    private static boolean usesReferencedTunnel(ConnectionConfig config) {
        return config != null && config.isUseSshTunnel()
                && config.getSshTunnelHostId() != null
                && !config.getSshTunnelHostId().isBlank();
    }

    private static String key(ConnectionConfig config, String targetHost, int targetPort) {
        return config.getId() + "_" + targetHost + ":" + targetPort;
    }

    private static SshTunnel createReferencedTunnel(ConnectionConfig config,
                                                     String targetHost, int targetPort) {
        ConnectionConfig sshHost = findSshHostConfig(config.getSshTunnelHostId());
        if (sshHost == null) {
            throw new RuntimeException("找不到引用的SSH主机配置(ID: "
                    + config.getSshTunnelHostId() + ")");
        }
        List<String> keyPaths = sshHost.isUseKey() ? sshHost.getPrivateKeyPaths() : null;
        String password = sshHost.isUsePassword() ? sshHost.getPassword() : null;
        if (!sshHost.isUsePassword() && sshHost.isUseKey() && sshHost.getPassword() != null) {
            password = sshHost.getPassword();
        }
        return new SshTunnel(
                sshHost.getHost(), sshHost.getPort(), sshHost.getUsername(), password,
                keyPaths, targetHost, targetPort);
    }

    private static ConnectionConfig findSshHostConfig(String hostId) {
        if (hostId == null) return null;
        try {
            for (ConnectionConfig config : ConfigManager.loadConnections()) {
                if (hostId.equals(config.getId())) return config;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    private static void disconnectQuietly(SshTunnel tunnel) {
        try {
            tunnel.disconnect();
        } catch (Exception ignored) {
        }
    }

    @FunctionalInterface
    interface TunnelFactory {
        SshTunnel create(ConnectionConfig config, String targetHost, int targetPort) throws Exception;
    }

    // 测试钩子：回归验证不需要真实 SSH 主机。
    static synchronized void setTunnelFactoryForTests(TunnelFactory factory) {
        closeAllForTests();
        tunnelFactory = factory;
    }

    static synchronized void resetForTests() {
        closeAllForTests();
        tunnelFactory = SshTunnelManager::createReferencedTunnel;
    }

    private static void closeAllForTests() {
        for (TunnelEntry entry : cache.values()) {
            disconnectQuietly(entry.tunnel);
        }
        cache.clear();
    }
}

package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshTunnelManagerTest {

    @AfterEach
    void tearDown() {
        SshTunnelManager.resetForTests();
    }

    @Test
    void staleLeaseCannotDisconnectReplacementTunnel() {
        List<FakeTunnel> tunnels = installFakeFactory();
        ConnectionConfig config = tunneledConfig();

        SshTunnelManager.TunnelLease first = SshTunnelManager.acquire(config);
        SshTunnelManager.TunnelLease second = SshTunnelManager.acquire(config);
        FakeTunnel oldTunnel = tunnels.getFirst();
        oldTunnel.fail();

        assertEquals(20002, first.refresh());
        FakeTunnel replacement = tunnels.get(1);

        // second 仍指向已经失效的旧实例；释放它不能按 key 误伤 replacement。
        second.close();
        assertTrue(replacement.isActive());
        assertEquals(0, replacement.disconnectCount);

        first.close();
        assertFalse(replacement.isActive());
        assertEquals(1, replacement.disconnectCount);
    }

    @Test
    void twoStaleLeasesCanMoveToSameReplacementWithoutKickingEachOther() {
        List<FakeTunnel> tunnels = installFakeFactory();
        ConnectionConfig config = tunneledConfig();

        SshTunnelManager.TunnelLease first = SshTunnelManager.acquire(config);
        SshTunnelManager.TunnelLease second = SshTunnelManager.acquire(config);
        tunnels.getFirst().fail();

        assertEquals(20002, first.refresh());
        assertEquals(20002, second.refresh());
        FakeTunnel replacement = tunnels.get(1);

        first.close();
        assertTrue(replacement.isActive());
        second.close();
        assertFalse(replacement.isActive());
    }

    @Test
    void closingLeaseIsIdempotent() {
        List<FakeTunnel> tunnels = installFakeFactory();
        ConnectionConfig config = tunneledConfig();

        SshTunnelManager.TunnelLease first = SshTunnelManager.acquire(config);
        SshTunnelManager.TunnelLease second = SshTunnelManager.acquire(config);
        FakeTunnel shared = tunnels.getFirst();

        first.close();
        first.close();
        assertTrue(shared.isActive());
        assertEquals(0, shared.disconnectCount);

        second.close();
        assertFalse(shared.isActive());
        assertEquals(1, shared.disconnectCount);
    }

    private static List<FakeTunnel> installFakeFactory() {
        List<FakeTunnel> tunnels = new ArrayList<>();
        AtomicInteger ports = new AtomicInteger(20000);
        SshTunnelManager.setTunnelFactoryForTests((config, host, port) -> {
            FakeTunnel tunnel = new FakeTunnel(ports.incrementAndGet());
            tunnels.add(tunnel);
            return tunnel;
        });
        return tunnels;
    }

    private static ConnectionConfig tunneledConfig() {
        ConnectionConfig config = new ConnectionConfig();
        config.setId("target-id");
        config.setHost("target.internal");
        config.setPort(22);
        config.setUseSshTunnel(true);
        config.setSshTunnelHostId("jump-id");
        return config;
    }

    private static final class FakeTunnel extends SshTunnel {
        private final int port;
        private boolean active;
        private int disconnectCount;

        FakeTunnel(int port) {
            super("jump", 22, "user", null, List.of(), "target", 22);
            this.port = port;
        }

        @Override
        public synchronized int connect() {
            active = true;
            return port;
        }

        @Override
        public int getForwardedLocalPort() {
            return port;
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public synchronized void disconnect() {
            if (active) {
                disconnectCount++;
            }
            active = false;
        }

        void fail() {
            active = false;
        }
    }
}

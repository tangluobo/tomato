package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteDesktopClientLauncherTest {

    @Test
    void buildsOneTimeSpiceConnectionFileWithTlsAndPassword() {
        ConnectionConfig config = remote(ConnectType.SPICE, "vm.example", 5900);
        config.setName("开发虚拟机");
        config.setUsername("operator");
        config.setPassword("pa\\ss\nword");
        config.setRemoteDesktopTlsPort(5901);
        config.setFullscreen(true);

        String content = RemoteDesktopClientLauncher.buildConnectionFile(config);

        assertTrue(content.contains("type=spice\n"));
        assertTrue(content.contains("host=vm.example\n"));
        assertTrue(content.contains("port=5900\n"));
        assertTrue(content.contains("tls-port=5901\n"));
        assertTrue(content.contains("username=operator\n"));
        assertTrue(content.contains("password=pa\\\\ss\\nword\n"));
        assertTrue(content.contains("fullscreen=1\n"));
        assertTrue(content.endsWith("delete-this-file=1\n"));
    }

    @Test
    void buildsVncFileWithoutEmptyPasswordOrSpiceTlsPort() {
        ConnectionConfig config = remote(ConnectType.VNC, "desktop.example", 0);
        config.setPassword("");
        config.setRemoteDesktopTlsPort(5901);

        String content = RemoteDesktopClientLauncher.buildConnectionFile(config);

        assertTrue(content.contains("type=vnc\n"));
        assertTrue(content.contains("port=5900\n"));
        assertFalse(content.contains("password="));
        assertFalse(content.contains("tls-port="));
    }

    @Test
    void buildsTigerVncCommandWithExplicitPortAndIpv6Brackets() {
        ConnectionConfig config = remote(ConnectType.VNC, "2001:db8::10", 5999);
        config.setFullscreen(true);

        List<String> command = RemoteDesktopClientLauncher.buildVncViewerCommand(
                Path.of("vncviewer.exe"), config);

        assertEquals(List.of("vncviewer.exe", "-FullScreen", "[2001:db8::10]::5999"), command);
    }

    private static ConnectionConfig remote(ConnectType type, String host, int port) {
        ConnectionConfig config = new ConnectionConfig("id", "remote", null, type);
        config.setHost(host);
        config.setPort(port);
        return config;
    }
}

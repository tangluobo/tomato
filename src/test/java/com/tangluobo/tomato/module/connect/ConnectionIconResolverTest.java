package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConnectionIconResolverTest {

    @Test
    void resolvesSpecificDatabaseProductLogo() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectType.POSTGRESQL);
        config.setDatabaseProfileId("kingbase");

        assertEquals("/images/connect/database-logos/kingbase.svg",
                ConnectionIconResolver.resolve(config));
    }

    @Test
    void resolvesSpecificToolIcon() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectType.TOOL);
        config.setToolType(ToolType.JSON_TOOL.getCode());

        assertEquals(ToolType.JSON_TOOL.getIconPath(), ConnectionIconResolver.resolve(config));
    }

    @Test
    void resolvesFolderAndProtocolIcons() {
        ConnectionConfig folder = new ConnectionConfig();
        ConnectionConfig sftp = new ConnectionConfig();
        sftp.setType(ConnectType.SFTP);

        assertEquals(ConnectionIconResolver.FOLDER_ICON, ConnectionIconResolver.resolve(folder));
        assertEquals(ConnectType.SFTP.getIconPath(), ConnectionIconResolver.resolve(sftp));
    }
}

package com.tangluobo.tomato.module.connect.service;

import com.mongodb.MongoClientSettings;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MongoServiceTest {

    @Test
    void buildsAnonymousHostAndPortSettings() {
        ConnectionConfig config = config("mongo.internal", 27018);

        MongoClientSettings settings = MongoService.buildSettings(config);

        assertNull(settings.getCredential());
        assertEquals("mongo.internal", settings.getClusterSettings().getHosts().getFirst().getHost());
        assertEquals(27018, settings.getClusterSettings().getHosts().getFirst().getPort());
        assertEquals("admin", MongoService.authenticationDatabase(config));
    }

    @Test
    void appliesCredentialsAndConfiguredAuthenticationDatabase() {
        ConnectionConfig config = config("localhost", 27017);
        config.setUsername("app_user");
        config.setPassword("secret");
        config.setDatabase("application");

        MongoClientSettings settings = MongoService.buildSettings(config);

        assertNotNull(settings.getCredential());
        assertEquals("app_user", settings.getCredential().getUserName());
        assertEquals("application", settings.getCredential().getSource());
        assertEquals("application", MongoService.authenticationDatabase(config));
    }

    @Test
    void acceptsStandardConnectionUri() {
        ConnectionConfig config = config("mongodb://db1.example:27017,db2.example:27018/?replicaSet=rs0", 27017);

        MongoClientSettings settings = MongoService.buildSettings(config);

        assertEquals(2, settings.getClusterSettings().getHosts().size());
        assertEquals("rs0", settings.getClusterSettings().getRequiredReplicaSetName());
    }

    @Test
    void rejectsUriTogetherWithSshTunnel() {
        ConnectionConfig config = config("mongodb+srv://cluster.example/", 27017);
        config.setUseSshTunnel(true);

        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> MongoService.buildSettings(config));

        assertEquals("连接 URI 模式暂不支持 SSH 通道，请填写单个 MongoDB 主机地址", error.getMessage());
    }

    private static ConnectionConfig config(String host, int port) {
        ConnectionConfig config = new ConnectionConfig("mongo-test", "Mongo", null, ConnectType.MONGODB);
        config.setHost(host);
        config.setPort(port);
        return config;
    }
}

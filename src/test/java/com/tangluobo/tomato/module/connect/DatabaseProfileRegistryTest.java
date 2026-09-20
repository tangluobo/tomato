package com.tangluobo.tomato.module.connect;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseProfileRegistryTest {

    @Test
    void profileIdsAreUniqueAndCoreProductsArePresent() {
        HashSet<String> ids = new HashSet<>();
        HashSet<String> logos = new HashSet<>();
        DatabaseProfileRegistry.profiles().forEach(profile -> assertTrue(ids.add(profile.id())));
        DatabaseProfileRegistry.profiles().forEach(profile -> assertTrue(
                logos.add(profile.iconPath()), "数据库产品应使用独立 Logo: " + profile.id()));

        assertAll(
                () -> assertNotNull(DatabaseProfileRegistry.find("mysql")),
                () -> assertNotNull(DatabaseProfileRegistry.find("mariadb")),
                () -> assertNotNull(DatabaseProfileRegistry.find("kingbase")),
                () -> assertNotNull(DatabaseProfileRegistry.find("opengauss")),
                () -> assertNotNull(DatabaseProfileRegistry.find("oracle")),
                () -> assertNotNull(DatabaseProfileRegistry.find("hive")));
    }

    @Test
    void compatibleProductsResolveToTheirWorkbenchDialect() {
        assertAll(
                () -> assertEquals(ConnectType.MYSQL,
                        DatabaseProfileRegistry.find("tidb").dialectType()),
                () -> assertEquals(ConnectType.POSTGRESQL,
                        DatabaseProfileRegistry.find("kingbase").dialectType()),
                () -> assertEquals(ConnectType.POSTGRESQL,
                        DatabaseProfileRegistry.find("opengauss").dialectType()),
                () -> assertEquals(ConnectType.ORACLE,
                        DatabaseProfileRegistry.find("oracle").dialectType()),
                () -> assertEquals(ConnectType.HIVE,
                        DatabaseProfileRegistry.find("hive").dialectType()));
    }

    @Test
    void oldConfigurationsResolveToDefaultProfiles() {
        ConnectionConfig config = new ConnectionConfig();
        config.setType(ConnectType.POSTGRESQL);

        assertEquals("postgresql", DatabaseProfileRegistry.resolve(config).id());
    }

    @Test
    void buildsProductSpecificJdbcUrls() {
        assertAll(
                () -> assertTrue(DatabaseProfileRegistry.find("mariadb")
                        .buildJdbcUrl("db.example", 3307, "shop")
                        .startsWith("jdbc:mariadb://db.example:3307/shop?")),
                () -> assertEquals("jdbc:postgresql://db.example:54321/app?connectTimeout=5&socketTimeout=300",
                        DatabaseProfileRegistry.find("kingbase")
                                .buildJdbcUrl("db.example", 54321, "app")),
                () -> assertEquals("jdbc:oracle:thin:@db.example:1521:ORCL",
                        DatabaseProfileRegistry.find("oracle")
                                .buildJdbcUrl("db.example", 1521, "ORCL")),
                () -> assertEquals("jdbc:oracle:thin:@//db.example:1521/ORCLPDB1",
                        DatabaseProfileRegistry.find("oracle")
                                .buildJdbcUrl("db.example", 1521, "/ORCLPDB1")),
                () -> assertEquals("jdbc:hive2://hive.example:10000/default",
                        DatabaseProfileRegistry.find("hive")
                                .buildJdbcUrl("hive.example", 10000, "")),
                () -> assertEquals("jdbc:hive2://hive.example:10000/analytics;transportMode=http;httpPath=cliservice",
                        DatabaseProfileRegistry.find("hive")
                                .buildJdbcUrl("hive.example", 10000,
                                        "analytics;transportMode=http;httpPath=cliservice")));
    }
}

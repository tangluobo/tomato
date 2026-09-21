package com.tangluobo.tomato.module.connect;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseLogoLoaderTest {

    @Test
    void rendersEveryDatabaseProductLogo() {
        assertAll(DatabaseProfileRegistry.profiles().stream().map(profile -> () -> {
            Image image = DatabaseLogoLoader.load(profile.iconPath(), 32, 32);
            assertNotNull(image, profile.id());
            assertFalse(image.isError(), profile.id());
            assertTrue(image.getWidth() > 0, profile.id());
            assertTrue(image.getHeight() > 0, profile.id());
        }));
    }

    @Test
    void rendersMongoDbLogo() {
        Image image = DatabaseLogoLoader.load(ConnectType.MONGODB.getIconPath(), 32, 32);
        assertNotNull(image);
        assertFalse(image.isError());
    }

    @Test
    void rendersRemoteDesktopLogos() {
        for (ConnectType type : new ConnectType[]{ConnectType.VNC, ConnectType.SPICE}) {
            Image image = DatabaseLogoLoader.load(type.getIconPath(), 32, 32);
            assertNotNull(image, type.name());
            assertFalse(image.isError(), type.name());
            assertTrue(image.getWidth() > 0, type.name());
            assertTrue(image.getHeight() > 0, type.name());
        }
    }

    @Test
    void rendersEveryConnectionAndToolIcon() {
        assertAll(java.util.Arrays.stream(ConnectType.values()).map(type -> () ->
                assertRenderable(type.name(), type.getIconPath())));
        assertAll(java.util.Arrays.stream(ToolType.values()).map(type -> () ->
                assertRenderable(type.name(), type.getIconPath())));
    }

    @Test
    void userFacingTypesAndToolsDoNotReuseIconPaths() {
        long connectionIconCount = java.util.Arrays.stream(ConnectType.values())
                .map(ConnectType::getIconPath).distinct().count();
        long toolIconCount = java.util.Arrays.stream(ToolType.values())
                .map(ToolType::getIconPath).distinct().count();

        assertEquals(ConnectType.values().length, connectionIconCount);
        assertEquals(ToolType.values().length, toolIconCount);
    }

    @Test
    void rendersTreeNodeSemanticIcons() {
        String[] paths = {
                "/images/connect/tree-icons/topic.svg",
                "/images/connect/tree-icons/consumer.svg",
                "/images/connect/tree-icons/cluster.svg",
                "/images/connect/tree-icons/message.svg",
                "/images/connect/tree-icons/broker.svg",
                "/images/connect/tree-icons/cloud-product.svg",
                "/images/connect/tree-icons/compute.svg",
                "/images/connect/tree-icons/domain.svg",
                "/images/connect/tree-icons/service.svg",
                "/images/connect/tree-icons/storage.svg"
        };
        assertAll(java.util.Arrays.stream(paths).map(path -> () -> assertRenderable(path, path)));
    }

    private static void assertRenderable(String name, String path) {
        Image image = DatabaseLogoLoader.load(path, 32, 32);
        assertNotNull(image, name);
        assertFalse(image.isError(), name);
        assertTrue(image.getWidth() > 0, name);
        assertTrue(image.getHeight() > 0, name);
    }
}

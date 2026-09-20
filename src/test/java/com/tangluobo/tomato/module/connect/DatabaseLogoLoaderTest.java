package com.tangluobo.tomato.module.connect;

import javafx.scene.image.Image;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}

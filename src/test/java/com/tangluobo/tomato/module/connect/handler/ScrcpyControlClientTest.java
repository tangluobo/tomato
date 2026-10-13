package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class ScrcpyControlClientTest {
    @Test
    void startsDirectControlOnConfiguredDeviceWithoutInjectingInput() throws Exception {
        String endpoint = System.getProperty("adb.control.integration.endpoint", "");
        org.junit.jupiter.api.Assumptions.assumeTrue(!endpoint.isBlank());
        AdbClient client = new AdbClient(endpoint, 5555,
                System.getProperty("adb.control.integration.executable", "adb"));
        try {
            client.connect();
            assertTrue(client.hasDirectControl(), client.controlDescription());
            assertEquals("scrcpy直接控制", client.controlDescription());
        } finally {
            client.closeSession();
        }
    }

    @Test
    void verifiesBundledServerAgainstOfficialReleaseDigest() throws Exception {
        assertEquals(90980, ScrcpyControlClient.bundledServer().length);
    }

    @Test
    void encodesTouchUsingDeviceCoordinatesAndReleasesPressure() {
        ByteBuffer down = ByteBuffer.wrap(ScrcpyControlClient.touchMessage(0, 42, 100, 200, 1080, 2400));
        assertEquals(32, down.remaining());
        assertEquals(2, down.get());
        assertEquals(0, down.get());
        assertEquals(42, down.getLong());
        assertEquals(100, down.getInt());
        assertEquals(200, down.getInt());
        assertEquals(1080, Short.toUnsignedInt(down.getShort()));
        assertEquals(2400, Short.toUnsignedInt(down.getShort()));
        assertEquals(65535, Short.toUnsignedInt(down.getShort()));
        assertEquals(0, down.getInt());
        assertEquals(0, down.getInt());
        ByteBuffer up = ByteBuffer.wrap(ScrcpyControlClient.touchMessage(1, 42, -10, 2500, 1080, 2400));
        assertEquals(1, up.get(1));
        assertEquals(0, up.getInt(10));
        assertEquals(2399, up.getInt(14));
        assertEquals(0, up.getShort(22));
        assertThrows(IllegalArgumentException.class, () -> ScrcpyControlClient.touchMessage(0, 1, 0, 0, 0, 10));
    }

    @Test
    void encodesHomeKeyAndNativeScrollWithCorrectSigns() {
        ByteBuffer key = ByteBuffer.wrap(ScrcpyControlClient.keyMessage(1, AdbClient.KEYCODE_HOME));
        assertEquals(14, key.remaining());
        assertEquals(0, key.get());
        assertEquals(1, key.get());
        assertEquals(3, key.getInt());
        assertEquals(0, key.getInt());
        assertEquals(0, key.getInt());
        ByteBuffer scroll = ByteBuffer.wrap(ScrcpyControlClient.scrollMessage(40, 50, 1920, 1080, -1, 1));
        assertEquals(21, scroll.remaining());
        assertEquals(3, scroll.get(0));
        assertEquals(-2048, scroll.getShort(13));
        assertEquals(2048, scroll.getShort(15));
    }
}

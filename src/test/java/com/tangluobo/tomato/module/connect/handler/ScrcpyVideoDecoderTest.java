package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class ScrcpyVideoDecoderTest {
    @Test
    void decodesANewKeyframeAfterTheConsumerStallsAndOldPacketsAreDiscarded() throws Exception {
        byte[] fixture = getClass().getResourceAsStream("/adb/red64x48.scrcpy").readAllBytes();
        var source = new DataInputStream(new ByteArrayInputStream(fixture));
        var config = ScrcpyVideoDecoder.readPacket(source);
        var keyframe = ScrcpyVideoDecoder.readPacket(source);
        assertTrue(keyframe.keyframe());
        var bytes = new java.io.ByteArrayOutputStream();
        var encoded = new java.io.DataOutputStream(bytes);
        encoded.writeLong(Long.MIN_VALUE);
        encoded.writeInt(config.data().length);
        encoded.write(config.data());
        for (int i = 0; i < 600; i++) {
            encoded.writeLong((1L << 62) | (i * 33_333L));
            encoded.writeInt(keyframe.data().length);
            encoded.write(keyframe.data());
        }
        var drained = new java.util.concurrent.CountDownLatch(1);
        var incoming = new ByteArrayInputStream(bytes.toByteArray()) {
            @Override public synchronized int read(byte[] target, int offset, int length) {
                int result = super.read(target, offset, length);
                if (available() == 0) drained.countDown();
                return result;
            }
        };
        try (var decoder = new ScrcpyVideoDecoder(new DataInputStream(incoming), () -> { })) {
            assertTrue(drained.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var frame = decoder.readFrame();
            assertEquals(64, frame.width());
            assertTrue(Byte.toUnsignedInt(frame.bgra()[2]) > 240);
        }
    }

    @Test
    void decodesARealH264ConfigAndFrameWithoutWaitingForAnotherFrame() throws Exception {
        try (DataInputStream stream = new DataInputStream(getClass().getResourceAsStream("/adb/red64x48.scrcpy"));
             ScrcpyVideoDecoder decoder = new ScrcpyVideoDecoder(stream, () -> { })) {
            AdbClient.VideoFrame frame = decoder.readFrame();
            assertEquals(64, frame.width());
            assertEquals(48, frame.height());
            assertEquals(64 * 48 * 4, frame.bgra().length);
            for (int i = 0; i < frame.bgra().length; i += 4) {
                assertTrue(Byte.toUnsignedInt(frame.bgra()[i]) < 10);
                assertTrue(Byte.toUnsignedInt(frame.bgra()[i + 1]) < 10);
                assertTrue(Byte.toUnsignedInt(frame.bgra()[i + 2]) > 240);
                assertEquals(255, Byte.toUnsignedInt(frame.bgra()[i + 3]));
            }
            decoder.close();
            assertThrows(IOException.class, decoder::readFrame);
        }
    }

    @Test
    void readsVideoDimensionsAndRejectsDisabledOrMalformedHeaders() throws Exception {
        byte[] header = ByteBuffer.allocate(12).putInt(0x68323634).putInt(1920).putInt(1080).array();
        assertEquals(new AdbClient.ScreenSize(1920, 1080),
                ScrcpyControlClient.readVideoHeader(new DataInputStream(new ByteArrayInputStream(header))));
        header[0] = 0;
        assertThrows(IOException.class, () -> ScrcpyControlClient.readVideoHeader(
                new DataInputStream(new ByteArrayInputStream(header))));
    }

    @Test
    void masksPacketFlagsAndRejectsHugeOrTruncatedPayloads() throws Exception {
        byte[] encoded = ByteBuffer.allocate(15).putLong((1L << 62) | 12345).putInt(3).put(new byte[]{1,2,3}).array();
        var packet = ScrcpyVideoDecoder.readPacket(new DataInputStream(new ByteArrayInputStream(encoded)));
        assertEquals(12345, packet.pts());
        assertFalse(packet.config());
        assertTrue(packet.keyframe());
        assertArrayEquals(new byte[]{1,2,3}, packet.data());
        byte[] tooLarge = ByteBuffer.allocate(12).putLong(0).putInt(Integer.MAX_VALUE).array();
        assertThrows(IOException.class, () -> ScrcpyVideoDecoder.readPacket(new DataInputStream(new ByteArrayInputStream(tooLarge))));
        assertThrows(IOException.class, () -> ScrcpyVideoDecoder.readPacket(new DataInputStream(new ByteArrayInputStream(java.util.Arrays.copyOf(encoded, 14)))));
    }
}

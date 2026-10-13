package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class ScrcpyPacketBufferTest {
    private static ScrcpyVideoDecoder.EncodedPacket frame(long pts, boolean key) {
        return new ScrcpyVideoDecoder.EncodedPacket(pts, false, key, new byte[]{7});
    }

    @Test void overflowDiscardsReferencesAndResumesAtKeyframeWithConfig() throws Exception {
        var queue = new ScrcpyPacketBuffer();
        queue.offer(new ScrcpyVideoDecoder.EncodedPacket(0, true, false, new byte[]{1, 2}));
        queue.offer(frame(0, true));
        queue.offer(frame(100_000, false));
        queue.offer(frame(300_000, false));
        assertFalse(queue.hasPending());
        queue.offer(frame(400_000, false));
        assertFalse(queue.hasPending());
        queue.offer(frame(500_000, true));
        var resumed = queue.take();
        assertEquals(500_000, resumed.pts());
        assertTrue(resumed.reset());
        assertArrayEquals(new byte[]{1, 2, 7}, resumed.data());
        queue.offer(frame(510_000, false));
        assertFalse(queue.take().reset());
    }

    @Test void halfHourOfSlowConsumptionNeverReplaysAnOldBacklog() throws Exception {
        var queue = new ScrcpyPacketBuffer();
        for (long pts = 0; pts <= 1_800_000_000L; pts += 10_000) {
            queue.offer(frame(pts, pts % 1_000_000 == 0));
            if (pts % 3_000_000 == 0 && queue.hasPending()) {
                assertTrue(pts - queue.take().pts() <= 250_000, "Stale compressed frame replayed");
            }
        }
    }

    @Test void newConfigurationReplacesOldAndCloseWakesBlockedConsumer() throws Exception {
        var queue = new ScrcpyPacketBuffer();
        queue.offer(new ScrcpyVideoDecoder.EncodedPacket(0, true, false, new byte[]{1}));
        queue.offer(frame(0, true));
        queue.take();
        queue.offer(new ScrcpyVideoDecoder.EncodedPacket(0, true, false, new byte[]{2}));
        queue.offer(frame(1, true));
        assertArrayEquals(new byte[]{2, 7}, queue.take().data());
        var waiting = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                assertThrows(IOException.class, queue::take));
        queue.close();
        assertNotNull(waiting.get(2, java.util.concurrent.TimeUnit.SECONDS));
    }
}

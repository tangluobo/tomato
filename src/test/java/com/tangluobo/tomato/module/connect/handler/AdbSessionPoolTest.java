package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdbSessionPoolTest {
    @Test
    void reusesOneSessionForRepeatedEntriesToTheSameEndpoint() {
        int initialSessions = AdbSessionPool.sessionCount();
        AtomicInteger statusUpdates = new AtomicInteger();
        AdbSessionPool.Listener firstListener = listener(statusUpdates);
        AdbSessionPool.Listener secondListener = listener(statusUpdates);

        try (AdbSessionPool.Lease first = AdbSessionPool.acquire(
                "reuse-session.invalid", 5555, "missing-adb-for-test", firstListener);
             AdbSessionPool.Lease second = AdbSessionPool.acquire(
                     "reuse-session.invalid:5555", 5555, "missing-adb-for-test", secondListener)) {
            assertEquals(initialSessions + 1, AdbSessionPool.sessionCount());
        }
    }

    @Test
    void sharesOneLiveDeviceSessionAcrossMultipleEntriesWhenConfigured() throws Exception {
        String endpoint = System.getProperty("adb.integration.endpoint", "").trim();
        Assumptions.assumeTrue(!endpoint.isEmpty(),
                "Set -Dadb.integration.endpoint to run the live ADB session test");
        String executable = System.getProperty("adb.integration.executable", "adb").trim();

        int initialSessions = AdbSessionPool.sessionCount();
        CountDownLatch firstFrame = new CountDownLatch(1);
        CountDownLatch secondFrame = new CountDownLatch(1);
        CountDownLatch secondFrameAfterFirstClosed = new CountDownLatch(1);
        AtomicBoolean firstClosed = new AtomicBoolean();

        AdbSessionPool.Listener firstListener = frameListener(firstFrame, null, null);
        AdbSessionPool.Listener secondListener = frameListener(
                secondFrame, firstClosed, secondFrameAfterFirstClosed);

        try (AdbSessionPool.Lease first = AdbSessionPool.acquire(
                endpoint, 5555, executable, firstListener)) {
            assertTrue(firstFrame.await(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS),
                    "The first entry did not receive a device frame");
            try (AdbSessionPool.Lease second = AdbSessionPool.acquire(
                    endpoint, 5555, executable, secondListener)) {
                assertTrue(secondFrame.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                        "The second entry did not receive the shared frame");
                assertEquals(initialSessions + 1, AdbSessionPool.sessionCount());

                firstClosed.set(true);
                first.close();
                assertTrue(secondFrameAfterFirstClosed.await(
                                Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                        "The shared stream stopped after the first entry closed");
                assertTrue(second.isConnected(), "The second entry lost the shared session");

                long holdSeconds = Long.getLong("adb.integration.holdSeconds", 0L);
                if (holdSeconds > 0) {
                    Thread.sleep(Duration.ofSeconds(holdSeconds));
                    assertTrue(second.isConnected(), "The shared session disconnected during the hold period");
                }
            }
        }

        CountDownLatch reentryFrame = new CountDownLatch(1);
        try (AdbSessionPool.Lease reentry = AdbSessionPool.acquire(
                endpoint, 5555, executable, frameListener(reentryFrame, null, null))) {
            assertTrue(reentry.isConnected(),
                    "Immediate re-entry did not reuse the still-live device session");
            assertTrue(reentryFrame.await(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                    "Immediate re-entry did not receive the shared frame");
            assertEquals(initialSessions + 1, AdbSessionPool.sessionCount());
        }
    }

    private static AdbSessionPool.Listener listener(AtomicInteger statusUpdates) {
        return new AdbSessionPool.Listener() {
            @Override
            public void onStatus(boolean connected, String message) {
                statusUpdates.incrementAndGet();
            }

            @Override
            public void onFrame(byte[] png, String endpoint) {
            }
        };
    }

    private static AdbSessionPool.Listener frameListener(CountDownLatch firstFrame,
                                                         AtomicBoolean afterFirstClosed,
                                                         CountDownLatch frameAfterFirstClosed) {
        return new AdbSessionPool.Listener() {
            @Override
            public void onVideoFrame(AdbClient.VideoFrame frame, AdbClient.ScreenSize displaySize,
                                     String endpoint) {
                onFrame(null, endpoint);
            }

            @Override
            public void onStatus(boolean connected, String message) {
            }

            @Override
            public void onFrame(byte[] png, String endpoint) {
                firstFrame.countDown();
                if (afterFirstClosed != null && afterFirstClosed.get()) {
                    frameAfterFirstClosed.countDown();
                }
            }
        };
    }
}

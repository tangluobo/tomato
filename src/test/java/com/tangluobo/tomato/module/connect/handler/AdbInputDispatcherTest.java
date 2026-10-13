package com.tangluobo.tomato.module.connect.handler;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AdbInputDispatcherTest {
    @Test
    void coalescesMovesWithoutReorderingPointerDownAndUp() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        try (AdbInputDispatcher dispatcher = new AdbInputDispatcher()) {
            dispatcher.submit(null, () -> {
                entered.countDown();
                try {
                    unblock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            dispatcher.submit(null, () -> events.add("down"));
            for (int i = 0; i < 1000; i++) {
                int position = i;
                assertTrue(dispatcher.submit("move", () -> events.add("move" + position)));
            }
            dispatcher.submit(null, () -> events.add("up"));
            dispatcher.submit(null, finished::countDown);
            unblock.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(List.of("down", "move999", "up"), events);
        } finally {
            unblock.countDown();
        }
    }

    @Test
    void boundsNonReplaceableBacklogAndRejectsAfterClose() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        try (AdbInputDispatcher dispatcher = new AdbInputDispatcher()) {
            dispatcher.submit(null, () -> {
                entered.countDown();
                try {
                    unblock.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            for (int i = 0; i < 64; i++) assertTrue(dispatcher.submit(null, () -> { }));
            assertFalse(dispatcher.submit(null, () -> fail("Rejected input must not run")));
            dispatcher.close();
            assertFalse(dispatcher.submit(null, () -> fail("Closed input must not run")));
        } finally {
            unblock.countDown();
        }
    }
}

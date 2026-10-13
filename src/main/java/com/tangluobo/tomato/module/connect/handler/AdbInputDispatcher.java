package com.tangluobo.tomato.module.connect.handler;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Ordered input with bounded backlog. Consecutive pointer moves retain only their latest position. */
final class AdbInputDispatcher implements AutoCloseable {
    private static final int CAPACITY = 64;
    private final Deque<Entry> queue = new ArrayDeque<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ADB-input");
        thread.setDaemon(true);
        return thread;
    });
    private boolean running;
    private boolean closed;

    synchronized boolean submit(String replaceable, Runnable action) {
        if (closed) return false;
        Entry tail = queue.peekLast();
        if (replaceable != null && tail != null && replaceable.equals(tail.replaceable())) {
            queue.removeLast();
        }
        if (queue.size() >= CAPACITY) {
            boolean removed = false;
            var iterator = queue.iterator();
            while (iterator.hasNext()) {
                if (iterator.next().replaceable() != null) {
                    iterator.remove();
                    removed = true;
                    break;
                }
            }
            if (!removed) return false;
        }
        queue.addLast(new Entry(replaceable, action));
        if (!running) {
            running = true;
            worker.execute(this::drain);
        }
        return true;
    }

    private void drain() {
        while (true) {
            Entry entry;
            synchronized (this) {
                entry = queue.pollFirst();
                if (entry == null) {
                    running = false;
                    return;
                }
            }
            try {
                entry.action().run();
            } catch (RuntimeException ignored) {
                // One faulty event must not prevent a later pointer-up from being sent.
            }
        }
    }

    synchronized void clear() {
        queue.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        queue.clear();
        worker.shutdownNow();
    }

    private record Entry(String replaceable, Runnable action) { }
}

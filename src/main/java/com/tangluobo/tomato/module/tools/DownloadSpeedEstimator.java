package com.tangluobo.tomato.module.tools;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/** Calculates a stable transfer rate from a monotonic byte counter over a rolling window. */
final class DownloadSpeedEstimator {

    static final Duration DEFAULT_WINDOW = Duration.ofSeconds(4);

    private final long windowNanos;
    private final Deque<Sample> samples = new ArrayDeque<>();

    DownloadSpeedEstimator() {
        this(DEFAULT_WINDOW);
    }

    DownloadSpeedEstimator(Duration window) {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("测速窗口必须大于 0");
        }
        windowNanos = window.toNanos();
    }

    synchronized long update(long transferredBytes, long nowNanos) {
        long currentBytes = Math.max(0L, transferredBytes);
        Sample newest = samples.peekLast();
        if (newest != null && (currentBytes < newest.bytes() || nowNanos <= newest.timeNanos())) {
            samples.clear();
        }
        samples.addLast(new Sample(nowNanos, currentBytes));

        long cutoff = nowNanos - windowNanos;
        while (samples.size() > 2) {
            Sample second = samples.stream().skip(1).findFirst().orElseThrow();
            if (second.timeNanos() > cutoff) break;
            samples.removeFirst();
        }

        Sample oldest = samples.peekFirst();
        newest = samples.peekLast();
        if (oldest == null || newest == null || oldest == newest) return 0L;
        long elapsed = newest.timeNanos() - oldest.timeNanos();
        long bytes = Math.max(0L, newest.bytes() - oldest.bytes());
        if (elapsed <= 0L || bytes == 0L) return 0L;
        return (long) Math.min(Long.MAX_VALUE, bytes * (1_000_000_000.0d / elapsed));
    }

    private record Sample(long timeNanos, long bytes) {
    }
}

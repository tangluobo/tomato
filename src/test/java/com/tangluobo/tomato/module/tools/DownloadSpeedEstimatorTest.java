package com.tangluobo.tomato.module.tools;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DownloadSpeedEstimatorTest {

    @Test
    void averagesTransferBytesAcrossRollingWindow() {
        DownloadSpeedEstimator estimator = new DownloadSpeedEstimator(Duration.ofSeconds(4));
        long mebibyte = 1024L * 1024L;

        assertEquals(0L, estimator.update(0L, 0L));
        assertEquals(mebibyte, estimator.update(mebibyte, 1_000_000_000L));
        assertEquals(mebibyte, estimator.update(2L * mebibyte, 2_000_000_000L));
        assertEquals(mebibyte, estimator.update(4L * mebibyte, 4_000_000_000L));
        assertEquals(3L * mebibyte / 4L,
                estimator.update(4L * mebibyte, 5_000_000_000L));
    }

    @Test
    void resetsSafelyWhenAnEngineStartsANewTransferCounter() {
        DownloadSpeedEstimator estimator = new DownloadSpeedEstimator(Duration.ofSeconds(4));

        estimator.update(10_000L, 1_000_000_000L);
        estimator.update(20_000L, 2_000_000_000L);
        assertEquals(0L, estimator.update(0L, 3_000_000_000L));
        assertEquals(5_000L, estimator.update(5_000L, 4_000_000_000L));
    }
}

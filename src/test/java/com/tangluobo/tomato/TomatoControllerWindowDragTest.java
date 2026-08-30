package com.tangluobo.tomato;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TomatoControllerWindowDragTest {

    @Test
    void ignoresPointerJitterButRecognizesARealDrag() {
        assertFalse(TomatoController.movedBeyondDragThreshold(100, 100, 102, 102));
        assertTrue(TomatoController.movedBeyondDragThreshold(100, 100, 104, 100));
        assertTrue(TomatoController.movedBeyondDragThreshold(100, 100, 100, 105));
    }

    @Test
    void maximizesOnlyWhenPointerReachesTheScreensWorkAreaTop() {
        double linuxWorkAreaTop = 28;

        assertFalse(TomatoController.isPointerAtTopEdge(41, linuxWorkAreaTop));
        assertTrue(TomatoController.isPointerAtTopEdge(33, linuxWorkAreaTop));
        assertTrue(TomatoController.isPointerAtTopEdge(28, linuxWorkAreaTop));
    }
}

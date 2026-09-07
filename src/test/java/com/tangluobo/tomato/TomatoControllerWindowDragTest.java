package com.tangluobo.tomato;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.geometry.Rectangle2D;
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

    @Test
    void preservesWindowSizeWhenMovingItBackToTheRemainingScreen() {
        Rectangle2D fitted = TomatoController.fitWindowBounds(
                new Rectangle2D(1920, 100, 1200, 900),
                new Rectangle2D(0, 0, 1920, 1040), 800, 600);

        assertEquals(1200, fitted.getWidth());
        assertEquals(900, fitted.getHeight());
        assertEquals(720, fitted.getMinX());
        assertEquals(100, fitted.getMinY());
    }

    @Test
    void shrinksOnlyWhenTheRemainingScreensWorkAreaCannotFitTheWindow() {
        Rectangle2D fitted = TomatoController.fitWindowBounds(
                new Rectangle2D(0, 0, 1600, 1000),
                new Rectangle2D(0, 0, 1366, 728), 800, 600);

        assertEquals(1366, fitted.getWidth());
        assertEquals(728, fitted.getHeight());
    }

    @Test
    void restoresAnUnexpectedlyTinyWindowToTheOperableMinimum() {
        Rectangle2D fitted = TomatoController.fitWindowBounds(
                new Rectangle2D(50, 50, 320, 200),
                new Rectangle2D(0, 0, 1920, 1040), 800, 600);

        assertEquals(800, fitted.getWidth());
        assertEquals(600, fitted.getHeight());
        assertEquals(50, fitted.getMinX());
        assertEquals(50, fitted.getMinY());
    }
}

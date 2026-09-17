package com.tangluobo.tomato.utils;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DialogPositionUtilTest {

    private static final List<Rectangle2D> SCREENS = List.of(
            new Rectangle2D(0, 0, 1920, 1080),
            new Rectangle2D(-2560, 0, 2560, 1440));

    @Test
    void selectsTheSecondaryScreenForANegativeCoordinateWindow() {
        int selected = DialogPositionUtil.screenIndexForBounds(
                new Rectangle2D(-2100, 120, 1200, 900), SCREENS);

        assertEquals(1, selected);
    }

    @Test
    void selectsTheScreenContainingMostOfASpanningWindow() {
        int selected = DialogPositionUtil.screenIndexForBounds(
                new Rectangle2D(-300, 100, 1000, 700), SCREENS);

        assertEquals(0, selected);
    }

    @Test
    void reportsNoScreenWhenTheWindowIsEntirelyOffScreen() {
        int selected = DialogPositionUtil.screenIndexForBounds(
                new Rectangle2D(5000, 5000, 800, 600), SCREENS);

        assertEquals(-1, selected);
    }
}

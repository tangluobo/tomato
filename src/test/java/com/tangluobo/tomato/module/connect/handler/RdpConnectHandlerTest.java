package com.tangluobo.tomato.module.connect.handler;

import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RdpConnectHandlerTest {

    private static final Rectangle2D FULL_HD_WORK_AREA = new Rectangle2D(0, 0, 1920, 1040);

    @Test
    void screenSizedDesktopUsesRealFullScreenInsteadOfClampedWindow() {
        assertTrue(RdpConnectHandler.desktopRequiresFullScreen(1920, 1080, FULL_HD_WORK_AREA));
    }

    @Test
    void desktopThatFitsWorkAreaRemainsWindowed() {
        assertFalse(RdpConnectHandler.desktopRequiresFullScreen(1280, 720, FULL_HD_WORK_AREA));
    }

    @Test
    void invalidDesktopDoesNotForceFullScreen() {
        assertFalse(RdpConnectHandler.desktopRequiresFullScreen(0, 1080, FULL_HD_WORK_AREA));
        assertFalse(RdpConnectHandler.desktopRequiresFullScreen(1920, 1080, null));
    }

    @Test
    void reusesConnectionWhoseOwnerIsHiddenByFullScreen() {
        assertTrue(RdpConnectHandler.shouldReuseRdpWindow(false, true));
    }

    @Test
    void doesNotReuseClosedWindow() {
        assertFalse(RdpConnectHandler.shouldReuseRdpWindow(false, false));
    }
}

package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GhosttyMouseEventTest {

    @Test
    public void storesRawTransportFields() {
        GhosttyMouseEvent event = new GhosttyMouseEvent(
            GhosttyMouseEvent.MOTION,
            GhosttyMouseEvent.BUTTON_FORWARD,
            GhosttyMouseEvent.MODIFIER_SHIFT | GhosttyMouseEvent.MODIFIER_ALT,
            12.5f,
            23.5f,
            100,
            200,
            10,
            20,
            3,
            4,
            5,
            6
        );

        assertEquals(GhosttyMouseEvent.MOTION, event.action);
        assertEquals(GhosttyMouseEvent.BUTTON_FORWARD, event.button);
        assertEquals(GhosttyMouseEvent.MODIFIER_SHIFT | GhosttyMouseEvent.MODIFIER_ALT, event.modifiers);
        assertEquals(12.5f, event.surfaceX, 0.0f);
        assertEquals(23.5f, event.surfaceY, 0.0f);
        assertEquals(100, event.screenWidthPx);
        assertEquals(200, event.screenHeightPx);
        assertEquals(10, event.cellWidthPx);
        assertEquals(20, event.cellHeightPx);
        assertEquals(3, event.paddingTopPx);
        assertEquals(4, event.paddingRightPx);
        assertEquals(5, event.paddingBottomPx);
        assertEquals(6, event.paddingLeftPx);
    }
}

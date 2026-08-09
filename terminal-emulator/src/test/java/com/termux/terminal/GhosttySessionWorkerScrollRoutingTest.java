package com.termux.terminal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GhosttySessionWorkerScrollRoutingTest {

    @Test
    public void mouseTrackingRoutesToMouseEncoder() {
        assertEquals(
            GhosttySessionWorker.SCROLL_ROUTE_MOUSE,
            GhosttySessionWorker.resolveScrollRoute(GhosttyNative.MODE_MOUSE_TRACKING, false)
        );
    }

    @Test
    public void alternateBufferWithoutMouseTrackingRoutesToArrowKeys() {
        assertEquals(
            GhosttySessionWorker.SCROLL_ROUTE_KEYS,
            GhosttySessionWorker.resolveScrollRoute(0, true)
        );
    }

    @Test
    public void ordinaryShellRoutesToViewport() {
        assertEquals(
            GhosttySessionWorker.SCROLL_ROUTE_VIEWPORT,
            GhosttySessionWorker.resolveScrollRoute(0, false)
        );
    }
}

package com.termux.terminal.compose.gpu

import org.junit.Test
import org.junit.Assert.assertEquals

class EmptyFrameBackgroundTest {
    @Test
    fun usesInitialColorWhenNoSnapshotWasSeen() {
        assertEquals(0xFF112233.toInt(), emptyFrameBackgroundColor(null, 0xFF112233.toInt()))
    }

    @Test
    fun prefersLastSeenSnapshotBackground() {
        assertEquals(
            0xFF445566.toInt(),
            emptyFrameBackgroundColor(0xFF445566.toInt(), 0xFF112233.toInt())
        )
    }
}

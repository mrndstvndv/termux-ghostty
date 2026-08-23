package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GlesProjectionTest {
    @Test
    fun mapsTopLeftPixelSpaceToOpenGlClipSpace() {
        val projection = GlesProjection(widthPx = 100, heightPx = 50)

        assertEquals(-1f, projection.xToNdc(0f), 0f)
        assertEquals(1f, projection.yToNdc(0f), 0f)
        assertEquals(1f, projection.xToNdc(100f), 0f)
        assertEquals(-1f, projection.yToNdc(50f), 0f)
    }

    @Test
    fun rectangleKeepsTopLeftOriginAndBottomLeftFramebufferConversionInOneStep() {
        val rectangle = GlesProjection(100, 50).rectToNdc(10f, 5f, 30f, 25f)

        assertEquals(-0.8f, rectangle[0], 0.0001f)
        assertEquals(0.8f, rectangle[1], 0.0001f)
        assertEquals(-0.4f, rectangle[2], 0.0001f)
        assertEquals(0f, rectangle[3], 0.0001f)
    }
}

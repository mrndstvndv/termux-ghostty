package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalModes
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class LazyTerminalFrameCacheTest {
    @Test
    fun `coalesces invalidations until the frame is consumed`() {
        var buildCount = 0
        val cache = LazyTerminalFrameCache { frame((++buildCount).toLong()) }

        assertEquals(1L, cache.currentFrame()?.sequence)
        cache.invalidate()
        cache.invalidate()
        cache.invalidate()

        assertEquals(2L, cache.currentFrame()?.sequence)
        assertEquals(2, buildCount)
        assertEquals(2L, cache.currentFrame()?.sequence)
        assertEquals(2, buildCount)
    }

    @Test
    fun `keeps the last frame when a dirty rebuild is unavailable`() {
        var nextFrame: TerminalFrame? = frame(1)
        val cache = LazyTerminalFrameCache { nextFrame }
        val firstFrame = cache.currentFrame()

        nextFrame = null
        cache.invalidate()

        assertSame(firstFrame, cache.currentFrame())
    }

    @Test
    fun `keeps the cached frame when rebuilding throws`() {
        var shouldThrow = false
        val cache = LazyTerminalFrameCache {
            if (shouldThrow) error("broken frame")
            frame(1)
        }
        val firstFrame = cache.currentFrame()

        shouldThrow = true
        cache.invalidate()

        assertThrows(IllegalStateException::class.java) { cache.currentFrame() }
        assertSame(firstFrame, cache.cachedFrame())
    }

    @Test
    fun `clear releases the cached frame without rebuilding`() {
        var buildCount = 0
        val cache = LazyTerminalFrameCache { frame((++buildCount).toLong()) }
        cache.currentFrame()

        cache.clear()

        assertNull(cache.currentFrame())
        assertEquals(1, buildCount)
    }

    private fun frame(sequence: Long): TerminalFrame =
        TerminalFrame(
            sequence = sequence,
            viewport = TerminalViewport(topRow = 0, rows = 0, columns = 0, transcriptRows = 0),
            cursor = TerminalCursor(column = 0, row = 0, visible = false, style = TerminalCursor.STYLE_BLOCK),
            modes = TerminalModes(
                reverseVideo = false,
                cursorKeysApplicationMode = false,
                keypadApplicationMode = false,
                mouseTrackingActive = false,
                alternateBufferActive = false
            ),
            palette = TerminalPalette.of(IntArray(0)),
            rows = emptyList(),
            linkLayout = null
        )
}

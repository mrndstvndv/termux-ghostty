package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.session.resolveTopRow
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalSessionBackendTest {

    @Test
    fun streamingAppendWhileScrolledUpRetainsViewport() {
        val topRow = resolveTopRow(
            previousTopRow = -5,
            viewportChanged = false,
            frameTopRow = 0,
            autoScrollDisabled = false,
            transcriptRows = 100,
            rowShift = 3
        )

        assertEquals(-8, topRow)
    }

    @Test
    fun streamingAppendAtBottomSnapsBackToBottom() {
        val topRow = resolveTopRow(
            previousTopRow = 0,
            viewportChanged = false,
            frameTopRow = 0,
            autoScrollDisabled = false,
            transcriptRows = 100,
            rowShift = 3
        )

        assertEquals(0, topRow)
    }

    @Test
    fun streamingAppendWithAutoScrollDisabledRetainsViewport() {
        val topRow = resolveTopRow(
            previousTopRow = 0,
            viewportChanged = false,
            frameTopRow = 0,
            autoScrollDisabled = true,
            transcriptRows = 100,
            rowShift = 3
        )

        assertEquals(-3, topRow)
    }

    @Test
    fun viewportScrollFrameUsesPublishedTopRow() {
        val topRow = resolveTopRow(
            previousTopRow = -5,
            viewportChanged = true,
            frameTopRow = -9,
            autoScrollDisabled = false,
            transcriptRows = 100,
            rowShift = 3
        )

        assertEquals(-9, topRow)
    }

    @Test
    fun shiftClampsAtTopOfHistory() {
        val topRow = resolveTopRow(
            previousTopRow = -2,
            viewportChanged = false,
            frameTopRow = 0,
            autoScrollDisabled = false,
            transcriptRows = 10,
            rowShift = 50
        )

        assertEquals(-10, topRow)
    }

    @Test
    fun publishedTopRowCoercedIntoHistoryBounds() {
        val topRow = resolveTopRow(
            previousTopRow = 0,
            viewportChanged = true,
            frameTopRow = -500,
            autoScrollDisabled = false,
            transcriptRows = 10,
            rowShift = 0
        )

        assertEquals(-10, topRow)
    }

    @Test
    fun idleFrameWithoutRowsDoesNotMoveScrolledUpViewport() {
        val topRow = resolveTopRow(
            previousTopRow = -5,
            viewportChanged = false,
            frameTopRow = 0,
            autoScrollDisabled = false,
            transcriptRows = 100,
            rowShift = 0
        )

        assertEquals(-5, topRow)
    }
}

package com.termux.terminal.compose.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollbarGeometryTest {

    @Test
    fun zeroTranscriptRowsRendersInvisibleScrollbar() {
        val geometry = TerminalScrollbarGeometry.calculate(
            topRow = 0,
            rowsVisible = 24,
            transcriptRows = 0,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )

        assertFalse(geometry.visible)
        assertEquals(0f, geometry.thumbLengthPx, 0f)
        assertEquals(0f, geometry.thumbOffsetPx, 0f)
    }

    @Test
    fun topOfTranscriptPositionsThumbAtTop() {
        val geometry = TerminalScrollbarGeometry.calculate(
            topRow = -100,
            rowsVisible = 25,
            transcriptRows = 100,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )

        assertTrue(geometry.visible)
        // totalRows = 125, fraction = 25/125 = 0.2, thumbLength = 1000 * 0.2 = 200px
        assertEquals(200f, geometry.thumbLengthPx, 0.01f)
        // progressFromTop = (-100 + 100) / 100 = 0.0 -> thumbOffset = 0px
        assertEquals(0f, geometry.thumbOffsetPx, 0.01f)
        assertTrue(geometry.isOnThumb(0f))
        assertTrue(geometry.isOnThumb(100f))
        assertTrue(geometry.isOnThumb(200f))
        assertFalse(geometry.isOnThumb(201f))
    }

    @Test
    fun bottomOfTranscriptPositionsThumbAtBottom() {
        val geometry = TerminalScrollbarGeometry.calculate(
            topRow = 0,
            rowsVisible = 25,
            transcriptRows = 100,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )

        assertTrue(geometry.visible)
        assertEquals(200f, geometry.thumbLengthPx, 0.01f)
        // scrollableTrack = 1000 - 200 = 800px, progressFromTop = (0 + 100) / 100 = 1.0 -> thumbOffset = 800px
        assertEquals(800f, geometry.thumbOffsetPx, 0.01f)
        assertFalse(geometry.isOnThumb(799f))
        assertTrue(geometry.isOnThumb(800f))
        assertTrue(geometry.isOnThumb(1000f))
    }

    @Test
    fun subCellSmoothScrollingMovesThumbContinuousFractionally() {
        // Halfway in history: topRow = -50
        val geomNoOffset = TerminalScrollbarGeometry.calculate(
            topRow = -50,
            rowsVisible = 25,
            transcriptRows = 100,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )
        // Midpoint offset: (1000 - 200) * 0.5 = 400px
        assertEquals(400f, geomNoOffset.thumbOffsetPx, 0.01f)

        // Sub-cell scroll down by 10px (0.5 cell)
        val geomSubCell = TerminalScrollbarGeometry.calculate(
            topRow = -50,
            rowsVisible = 25,
            transcriptRows = 100,
            visualScrollOffsetPx = 10f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )
        // effectiveTopRow = -50 - 0.5 = -50.5 -> progressFromTop = (-50.5 + 100) / 100 = 0.495
        // thumbOffset = 800 * 0.495 = 396px
        assertEquals(396f, geomSubCell.thumbOffsetPx, 0.01f)
    }

    @Test
    fun minThumbLengthIsEnforcedOnLargeHistory() {
        val geometry = TerminalScrollbarGeometry.calculate(
            topRow = 0,
            rowsVisible = 20,
            transcriptRows = 10000,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 60f
        )

        assertTrue(geometry.visible)
        assertEquals(60f, geometry.thumbLengthPx, 0.01f)
        assertEquals(940f, geometry.thumbOffsetPx, 0.01f)
    }

    @Test
    fun targetTopRowForPointerYComputesCorrectHistoryRow() {
        val geometry = TerminalScrollbarGeometry.calculate(
            topRow = -50,
            rowsVisible = 25,
            transcriptRows = 100,
            visualScrollOffsetPx = 0f,
            cellHeightPx = 20f,
            trackHeightPx = 1000f,
            minThumbLengthPx = 50f
        )
        // scrollableTrack = 800px.
        // Drag top to 0px -> topRow = -100
        assertEquals(-100, geometry.targetTopRowForPointerY(pointerY = 100f, dragAnchorOffsetY = 100f))
        // Drag bottom to 800px -> topRow = 0
        assertEquals(0, geometry.targetTopRowForPointerY(pointerY = 900f, dragAnchorOffsetY = 100f))
        // Drag middle to 400px -> topRow = -50
        assertEquals(-50, geometry.targetTopRowForPointerY(pointerY = 500f, dragAnchorOffsetY = 100f))
    }
}

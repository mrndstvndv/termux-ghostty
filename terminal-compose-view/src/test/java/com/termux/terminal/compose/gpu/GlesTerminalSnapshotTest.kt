package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesTerminalSnapshotTest {
    @Test
    fun snapshotCarriesCompleteFrameAndVisualConfig() {
        val visual = GlesTerminalVisualConfig(fontSizePx = 18f)
        val snapshot = testSnapshot(21L).copy(visual = visual)

        assertEquals(18f, snapshot.visual.fontSizePx)
        assertEquals(21L, snapshot.contentRevision)
        assertEquals(21L, snapshot.presentationRevision)
        assertTrue(snapshot.frame.rows.size == snapshot.frame.rowsVisible)
    }

    @Test
    fun presentationRevisionCanAdvanceWithoutChangingTerminalSequence() {
        val snapshot = testSnapshot(22L).copy(visualOffsetPx = 7.5f)

        val next = snapshot.withPresentationRevision(23L)

        assertEquals(22L, next.frame.sequence)
        assertEquals(23L, next.presentationRevision)
        assertEquals(7.5f, next.visualOffsetPx, 0f)
    }
}

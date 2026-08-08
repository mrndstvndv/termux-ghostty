package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TerminalLinkLayoutTest {
    @Test
    fun rowContentIdentityIgnoresFrameSequence() {
        val first = layout(frameSequence = 1L)
        val second = layout(frameSequence = 2L)

        assertEquals(first.rowContentHash(0), second.rowContentHash(0))
        assertEquals(0L, first.rowContentHash(1))
    }

    @Test
    fun rowContentIdentityChangesOnlyForChangedRows() {
        val first = layout(frameSequence = 1L)
        val changed = TerminalLinkLayout(
            frameSequence = 2L,
            topRow = 0,
            rows = 2,
            columns = 8,
            segmentsPerRow = listOf(
                listOf(TerminalLinkSegment(1, 4, "https://example.com")),
                listOf(TerminalLinkSegment(2, 5, "https://changed.example"))
            )
        )

        assertEquals(first.rowContentHash(0), changed.rowContentHash(0))
        assertNotEquals(first.rowContentHash(1), changed.rowContentHash(1))
    }

    private fun layout(frameSequence: Long): TerminalLinkLayout =
        TerminalLinkLayout(
            frameSequence = frameSequence,
            topRow = 0,
            rows = 2,
            columns = 8,
            segmentsPerRow = listOf(
                listOf(TerminalLinkSegment(1, 4, "https://example.com")),
                emptyList()
            )
        )
}

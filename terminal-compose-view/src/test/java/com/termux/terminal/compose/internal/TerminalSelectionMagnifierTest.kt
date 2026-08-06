package com.termux.terminal.compose.internal

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalSelectionMagnifierTest {
    private val metrics = TerminalMetrics.of(
        cellWidthPx = 10f,
        cellHeightPx = 20f,
        ascentPx = -15f,
        lineSpacingAndAscentPx = 0f,
        viewportWidthPx = 100,
        viewportHeightPx = 100
    )

    @Test
    fun hiddenMagnifierRemovesItsModifier() {
        val modifier = Modifier

        assertSame(
            modifier,
            modifier.terminalSelectionMagnifier(visible = false) { error("not evaluated") }
        )
    }

    @Test
    fun sourceCentersTheCharacterAtTheStartHandle() {
        assertEquals(
            Offset(15f, 90f),
            selectionMagnifierSource(
                endpoint = SelectionHandleEndpoint.START,
                handlePosition = Offset(10f, 100f),
                metrics = metrics
            )
        )
    }

    @Test
    fun sourceCentersTheCharacterAtTheEndHandle() {
        assertEquals(
            Offset(85f, 90f),
            selectionMagnifierSource(
                endpoint = SelectionHandleEndpoint.END,
                handlePosition = Offset(90f, 100f),
                metrics = metrics
            )
        )
    }

    @Test
    fun sourceUsesTheSelectedHandleCharacterInsteadOfTheDragPosition() {
        val selection = TerminalSelection(
            startCol = 2,
            startRow = 3,
            endCol = 7,
            endRow = 3
        )

        assertEquals(
            Offset(25f, 70f),
            selectionMagnifierSourceForSelection(
                endpoint = SelectionHandleEndpoint.START,
                selection = selection,
                topRow = 0,
                metrics = metrics
            )
        )
        assertEquals(
            Offset(75f, 70f),
            selectionMagnifierSourceForSelection(
                endpoint = SelectionHandleEndpoint.END,
                selection = selection,
                topRow = 0,
                metrics = metrics
            )
        )
    }

    @Test
    fun sourceIsClampedToTheViewport() {
        assertEquals(
            Offset(0f, 0f),
            selectionMagnifierSource(
                endpoint = SelectionHandleEndpoint.END,
                handlePosition = Offset(-100f, -100f),
                metrics = metrics
            )
        )
    }
}

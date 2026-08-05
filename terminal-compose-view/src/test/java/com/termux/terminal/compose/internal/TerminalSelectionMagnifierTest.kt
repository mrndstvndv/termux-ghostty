package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalMetrics
import org.junit.Assert.assertEquals
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

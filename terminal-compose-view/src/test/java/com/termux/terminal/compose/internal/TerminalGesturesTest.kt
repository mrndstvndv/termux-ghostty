package com.termux.terminal.compose.internal

import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalPointerEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalGesturesTest {
    @Test
    fun scrollCommandPreservesTouchPositionForPaneRouting() {
        val command = scrollCommandForGesture(
            deltaRows = 2,
            touchPosition = Offset(120f, 640f),
            metrics = metrics()
        )

        assertEquals(-2, command.rowsDown)
        assertEquals(120f, command.xPx)
        assertEquals(640f, command.yPx)
        assertEquals(400, command.geometry.viewportWidthPx)
        assertEquals(800, command.geometry.viewportHeightPx)
    }

    @Test
    fun tapBuildsMousePairWithoutDependingOnRenderedModeSnapshot() {
        val events = tapMouseEventsForPosition(120f, 640f, metrics())

        assertEquals(2, events.size)
        assertEquals(TerminalPointerEvent.Action.PRESS, events[0].action)
        assertEquals(TerminalPointerEvent.Action.RELEASE, events[1].action)
        assertTrue(events.all { it.button == TerminalPointerEvent.BUTTON_LEFT })
        assertTrue(events.all { it.xPx == 120f && it.yPx == 640f })
    }

    @Test
    fun pinchZoomAccumulatesBelowThresholdWithoutChangingFontSize() {
        var changedSize = 0
        val nextScale = applyPinchZoomStep(
            zoomChange = 1.05f,
            scaleAccumulator = 1.0f,
            currentFontSize = 14,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = { changedSize = it }
        )

        assertEquals(1.05f, nextScale, 0.001f)
        assertEquals(0, changedSize)
    }

    @Test
    fun pinchZoomStepsContinuouslyAcrossMultipleThresholdCrossings() {
        var currentSize = 14
        val fontSizes = mutableListOf<Int>()

        // First step crossing threshold (> 1.1)
        var scale = applyPinchZoomStep(
            zoomChange = 1.06f,
            scaleAccumulator = 1.05f, // 1.05 * 1.06 = 1.113
            currentFontSize = currentSize,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = {
                currentSize = it
                fontSizes.add(it)
            }
        )

        assertEquals(1.0f, scale, 0.001f)
        assertEquals(listOf(16), fontSizes)

        // Continuous pinch in the same gesture: scale accumulates from 1.0 again
        scale = applyPinchZoomStep(
            zoomChange = 1.05f,
            scaleAccumulator = scale,
            currentFontSize = currentSize,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = {
                currentSize = it
                fontSizes.add(it)
            }
        )
        assertEquals(1.05f, scale, 0.001f)
        assertEquals(listOf(16), fontSizes)

        // Second step crossing threshold (> 1.1) during the same ongoing pinch
        scale = applyPinchZoomStep(
            zoomChange = 1.06f,
            scaleAccumulator = scale,
            currentFontSize = currentSize,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = {
                currentSize = it
                fontSizes.add(it)
            }
        )
        assertEquals(1.0f, scale, 0.001f)
        assertEquals(listOf(16, 18), fontSizes)
    }

    @Test
    fun pinchZoomOutStepsContinuouslyAndClampsToMinimum() {
        var currentSize = 10
        val fontSizes = mutableListOf<Int>()

        // Zoom out crossing threshold (< 0.9)
        var scale = applyPinchZoomStep(
            zoomChange = 0.88f,
            scaleAccumulator = 1.0f,
            currentFontSize = currentSize,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = {
                currentSize = it
                fontSizes.add(it)
            }
        )

        assertEquals(1.0f, scale, 0.001f)
        assertEquals(listOf(8), fontSizes)

        // Further zoom out hits minimum and does not change below minFontSize
        scale = applyPinchZoomStep(
            zoomChange = 0.88f,
            scaleAccumulator = scale,
            currentFontSize = currentSize,
            minFontSize = 8,
            maxFontSize = 32,
            onFontSizeChange = {
                currentSize = it
                fontSizes.add(it)
            }
        )

        assertEquals(1.0f, scale, 0.001f)
        assertEquals(listOf(8), fontSizes)
    }

    private fun metrics() = TerminalMetrics.of(
        cellWidthPx = 10f,
        cellHeightPx = 20f,
        ascentPx = 15f,
        lineSpacingAndAscentPx = 16f,
        viewportWidthPx = 400,
        viewportHeightPx = 800
    )
}


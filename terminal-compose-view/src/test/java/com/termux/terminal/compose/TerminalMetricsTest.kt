package com.termux.terminal.compose

import android.graphics.Typeface
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalMetricsTest {
    @Test
    fun exposesRawAndRoundedCellMetricsFromOneFontCalculation() {
        val metrics = TerminalMetrics.from(
            fontSizePx = 14f,
            typeface = Typeface.MONOSPACE,
            viewportWidthPx = 320,
            viewportHeightPx = 200
        )
        val shared = TerminalFontMetrics.from(Typeface.MONOSPACE, 14f)

        assertTrue(metrics.measuredCellWidthPx >= 1f)
        assertEquals(
            metrics.measuredCellWidthPx.roundToInt().coerceAtLeast(1).toFloat(),
            metrics.cellWidthPx,
            0f
        )
        assertEquals(
            metrics.cellWidthPx / metrics.measuredCellWidthPx,
            metrics.textScaleX,
            0f
        )
        assertEquals(shared.lineSpacingPx.toFloat(), metrics.cellHeightPx, 0f)
        assertEquals(shared.ascentPx.toFloat(), metrics.fontAscentPx, 0f)
        assertEquals(shared.lineSpacingAndAscentPx.toFloat(), metrics.lineSpacingAndAscentPx, 0f)
    }

    @Test
    fun visualColumnGeometryUsesRoundedWidthAtFractionalBoundaries() {
        val metrics = TerminalMetrics.from(
            fontSizePx = 13f,
            typeface = Typeface.MONOSPACE,
            viewportWidthPx = 100,
            viewportHeightPx = 100
        )

        assertEquals(0f, metrics.columnToX(0), 0f)
        assertEquals(metrics.cellWidthPx, metrics.columnToX(1), 0f)
        assertEquals(1, metrics.xToColumn(metrics.cellWidthPx + 0.01f))
        assertEquals(0, metrics.xToColumn(metrics.cellWidthPx - 0.01f))
    }
}

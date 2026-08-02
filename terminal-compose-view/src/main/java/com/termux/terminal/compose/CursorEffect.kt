package com.termux.terminal.compose

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * Consumer-implemented cursor visual effect (e.g. a warp/sweep/tail trail).
 *
 * The consumer owns all colors, geometry, easing, and duration. The library
 * owns previous/current cursor tracking, move timestamps, first-observation
 * behavior, and reset when the backend identity or the effect instance
 * changes. A bounded [maxDurationSeconds] lets the canvas stop scheduling
 * frames once a transient effect has finished.
 */
interface CursorEffect {

    /** Declared animation duration in seconds; must be positive. */
    val maxDurationSeconds: Float

    /**
     * Draws the effect over the terminal frame. [metrics] is the same cell
     * geometry used by the renderer. [state] reflects the tracked cursor
     * positions; [timeSeconds] is the canvas animation clock. The effect must
     * tolerate its first frame, cursor visibility changes, backend replacement,
     * resize, and stale/empty frames.
     */
    fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        metrics: TerminalMetrics,
        state: CursorEffectState,
        timeSeconds: Float
    )
}

/**
 * Cursor movement state tracked by the canvas and handed to [CursorEffect].
 * Reset whenever the backend identity or effect instance changes.
 */
class CursorEffectState {
    var hasPreviousPosition: Boolean = false
        internal set
    var previousColumn: Int = -1
        internal set
    var previousRow: Int = -1
        internal set
    var currentColumn: Int = -1
        internal set
    var currentRow: Int = -1
        internal set
    var changeSeconds: Float = 0f
        internal set

    internal fun observe(column: Int, row: Int, timeSeconds: Float) {
        if (column == currentColumn && row == currentRow) return
        if (hasPreviousPosition) {
            previousColumn = currentColumn
            previousRow = currentRow
        }
        currentColumn = column
        currentRow = row
        changeSeconds = timeSeconds
        hasPreviousPosition = true
    }

    internal fun reset() {
        hasPreviousPosition = false
        previousColumn = -1
        previousRow = -1
        currentColumn = -1
        currentRow = -1
        changeSeconds = 0f
    }
}

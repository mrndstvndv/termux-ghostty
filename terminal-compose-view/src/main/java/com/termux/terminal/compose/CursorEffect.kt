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
 * Reset whenever the backend identity, effect instance, frame sequence, or viewport geometry changes.
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

    private var lastFrameSequence = Long.MIN_VALUE
    private var lastTopRow = Int.MIN_VALUE
    private var lastRowsVisible = -1
    private var lastColumns = -1

    /** Tracks only cursor movement across contiguous, stable frame geometry. */
    internal fun observe(frame: TerminalFrame, timeSeconds: Float) {
        val hasPreviousFrame = lastFrameSequence != Long.MIN_VALUE
        val sequenceWentBack = frame.sequence < lastFrameSequence
        val sequenceSkipped = frame.sequence > lastFrameSequence + 1L
        val frameDiscontinuity = hasPreviousFrame && (
            sequenceWentBack ||
                sequenceSkipped ||
                frame.topRow != lastTopRow ||
                frame.rowsVisible != lastRowsVisible ||
                frame.columns != lastColumns
            )
        if (frameDiscontinuity) reset()

        lastFrameSequence = frame.sequence
        lastTopRow = frame.topRow
        lastRowsVisible = frame.rowsVisible
        lastColumns = frame.columns
        observeCursor(frame.cursor, timeSeconds)
    }

    /** Kept for focused state tests that do not need frame continuity metadata. */
    internal fun observe(cursor: TerminalCursor, timeSeconds: Float) {
        observeCursor(cursor, timeSeconds)
    }

    private fun observeCursor(cursor: TerminalCursor, timeSeconds: Float) {
        if (!cursor.visible) return
        if (cursor.column == currentColumn && cursor.row == currentRow) return
        if (hasPreviousPosition) {
            previousColumn = currentColumn
            previousRow = currentRow
        }
        currentColumn = cursor.column
        currentRow = cursor.row
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
        lastFrameSequence = Long.MIN_VALUE
        lastTopRow = Int.MIN_VALUE
        lastRowsVisible = -1
        lastColumns = -1
    }
}

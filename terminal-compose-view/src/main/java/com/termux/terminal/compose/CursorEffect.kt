package com.termux.terminal.compose

/** Cursor trail preset.
 *
 * Cursor movement is captured from complete immutable [TerminalFrame] publications and rendered
 * by the OpenGL ES pipeline without reading backend state during drawing.
 */
enum class CursorEffect(
    val maxDurationSeconds: Float
) {
    WARP(0.2f),
    SWEEP(0.2f),
    TAIL(0.09f)
}

/** Immutable cursor movement publication safe to consume on a renderer thread. */
data class CursorEffectSnapshot(
    val effect: CursorEffect,
    val previousColumn: Int,
    val previousRow: Int,
    val currentColumn: Int,
    val currentRow: Int,
    val changeSeconds: Float
)

/**
 * Cursor movement state tracked from complete canvas frames and published to the active renderer.
 * Reset whenever the effect, frame sequence, or viewport geometry changes.
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

    /** Captures one immutable movement after both endpoints have been observed. */
    internal fun snapshot(effect: CursorEffect): CursorEffectSnapshot? {
        if (!hasPreviousPosition || !hasCompleteMovement()) {
            return null
        }
        return CursorEffectSnapshot(
            effect = effect,
            previousColumn = previousColumn,
            previousRow = previousRow,
            currentColumn = currentColumn,
            currentRow = currentRow,
            changeSeconds = changeSeconds
        )
    }

    private fun hasCompleteMovement(): Boolean =
        previousColumn >= 0 &&
            previousRow >= 0 &&
            currentColumn >= 0 &&
            currentRow >= 0

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

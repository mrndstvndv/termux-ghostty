package com.termux.terminal.compose.internal

import kotlin.math.roundToInt

/**
 * Pure, immutable geometry calculations for the terminal scrollbar thumb and track.
 */
internal data class TerminalScrollbarGeometry(
    val visible: Boolean,
    val trackHeightPx: Float,
    val thumbLengthPx: Float,
    val thumbOffsetPx: Float,
    val transcriptRows: Int
) {
    /** Checks whether a given vertical pointer Y position falls within the thumb bounds. */
    fun isOnThumb(y: Float): Boolean =
        visible && y >= thumbOffsetPx && y <= (thumbOffsetPx + thumbLengthPx)

    /** Computes the target [topRow] resulting from a thumb drag to [pointerY]. */
    fun targetTopRowForPointerY(pointerY: Float, dragAnchorOffsetY: Float = thumbLengthPx / 2f): Int {
        if (!visible || transcriptRows <= 0) return 0
        val scrollableTrackPx = trackHeightPx - thumbLengthPx
        if (scrollableTrackPx <= 0f) return 0
        val desiredThumbOffset = (pointerY - dragAnchorOffsetY).coerceIn(0f, scrollableTrackPx)
        val progressFromTop = desiredThumbOffset / scrollableTrackPx
        val topRow = -transcriptRows + (progressFromTop * transcriptRows)
        return topRow.roundToInt().coerceIn(-transcriptRows, 0)
    }

    companion object {
        fun calculate(
            topRow: Int,
            rowsVisible: Int,
            transcriptRows: Int,
            visualScrollOffsetPx: Float,
            cellHeightPx: Float,
            trackHeightPx: Float,
            minThumbLengthPx: Float
        ): TerminalScrollbarGeometry {
            if (transcriptRows <= 0 || rowsVisible <= 0 || trackHeightPx <= 0f) {
                return TerminalScrollbarGeometry(
                    visible = false,
                    trackHeightPx = trackHeightPx.coerceAtLeast(0f),
                    thumbLengthPx = 0f,
                    thumbOffsetPx = 0f,
                    transcriptRows = transcriptRows.coerceAtLeast(0)
                )
            }

            val totalRows = transcriptRows + rowsVisible
            val visibleFraction = (rowsVisible.toFloat() / totalRows.toFloat()).coerceIn(0f, 1f)
            val thumbLengthPx = (trackHeightPx * visibleFraction)
                .coerceIn(minThumbLengthPx.coerceAtMost(trackHeightPx), trackHeightPx)
            val scrollableTrackPx = trackHeightPx - thumbLengthPx

            val effectiveTopRow = if (cellHeightPx > 0f) {
                topRow.toDouble() - (visualScrollOffsetPx / cellHeightPx)
            } else {
                topRow.toDouble()
            }

            val progressFromTop = ((effectiveTopRow + transcriptRows) / transcriptRows.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()

            val thumbOffsetPx = scrollableTrackPx * progressFromTop

            return TerminalScrollbarGeometry(
                visible = true,
                trackHeightPx = trackHeightPx,
                thumbLengthPx = thumbLengthPx,
                thumbOffsetPx = thumbOffsetPx,
                transcriptRows = transcriptRows
            )
        }
    }
}

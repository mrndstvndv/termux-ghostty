package com.mrndtvndv.term.gpu

import com.termux.terminal.compose.TerminalFrame

internal const val EMPTY_FRAME_CHECKSUM = "00000000"

/**
 * Checksums the final immutable frame consumed by the renderer.
 *
 * The definition intentionally reads only published frame data: viewport,
 * cursor, modes, palette, row text/layout/style, and link segments. Scene
 * inputs and presentation-only selection are not checksum inputs.
 */
internal fun checksumForFrame(frame: TerminalFrame): String {
    var hash = -3750763034362895579L
    hash = mixChecksum(hash, frame.topRow)
    hash = mixChecksum(hash, frame.rowsVisible)
    hash = mixChecksum(hash, frame.columns)
    hash = mixChecksum(hash, frame.viewport.transcriptRows)
    hash = mixChecksum(hash, frame.cursor.column)
    hash = mixChecksum(hash, frame.cursor.row)
    hash = mixChecksum(hash, frame.cursor.visible)
    hash = mixChecksum(hash, frame.cursor.style)
    hash = mixChecksum(hash, frame.modes.reverseVideo)
    hash = mixChecksum(hash, frame.modes.cursorKeysApplicationMode)
    hash = mixChecksum(hash, frame.modes.keypadApplicationMode)
    hash = mixChecksum(hash, frame.modes.mouseTrackingActive)
    hash = mixChecksum(hash, frame.modes.alternateBufferActive)
    hash = mixChecksum(hash, frame.palette.version)
    for (index in 0..258) hash = mixChecksum(hash, frame.palette.color(index))

    for (row in frame.rows) {
        hash = mixChecksum(hash, row.columns)
        hash = mixChecksum(hash, row.charsUsed)
        hash = mixChecksum(hash, row.contentHash)
        hash = mixChecksum(hash, row.isLineWrap)
        for (column in 0 until frame.columns) {
            hash = mixChecksum(hash, row.style(column))
            hash = mixChecksum(hash, row.cellDisplayWidth(column))
            val range = row.cellTextRange(column)
            hash = mixChecksum(hash, range?.first ?: -1)
            hash = mixChecksum(hash, range?.last ?: -1)
        }
        for (char in row.text()) hash = mixChecksum(hash, char.code)
    }

    val links = frame.linkLayout
    if (links == null) {
        hash = mixChecksum(hash, 0)
    } else {
        hash = mixChecksum(hash, links.frameSequence)
        hash = mixChecksum(hash, links.topRow)
        hash = mixChecksum(hash, links.rows)
        hash = mixChecksum(hash, links.columns)
        for (rowIndex in 0 until links.rows) {
            for (segment in links.rowSegments(rowIndex)) {
                hash = mixChecksum(hash, segment.startColumn)
                hash = mixChecksum(hash, segment.endColumnExclusive)
                for (char in segment.url) hash = mixChecksum(hash, char.code)
            }
        }
    }
    return (hash and 0xffffffffL).toString(16).padStart(8, '0')
}

private fun mixChecksum(hash: Long, value: Boolean): Long =
    mixChecksum(hash, if (value) 1 else 0)

private fun mixChecksum(hash: Long, value: Int): Long =
    (hash xor value.toLong()) * 1099511628211L

private fun mixChecksum(hash: Long, value: Long): Long =
    (hash xor value) * 1099511628211L

package com.mrndtvndv.term.gpu

import com.termux.terminal.compose.TerminalFrame

/**
 * Validates the immutable pair handed from the observed Compose state to the
 * renderer adapter. The adapter must never re-read backend state while
 * publishing a snapshot.
 */
internal fun requireGpuLabPublication(
    backendState: GpuLabBackendState,
    frame: TerminalFrame
) {
    check(frame.sequence == backendState.sequence) {
        "GPU lab frame sequence ${frame.sequence} != state sequence ${backendState.sequence}"
    }
    check(frame.topRow == backendState.topRow) {
        "GPU lab frame topRow ${frame.topRow} != state topRow ${backendState.topRow}"
    }
    check(frame.columns == backendState.size.columns) {
        "GPU lab frame columns ${frame.columns} != state columns ${backendState.size.columns}"
    }
    check(frame.rowsVisible == backendState.size.rows) {
        "GPU lab frame rows ${frame.rowsVisible} != state rows ${backendState.size.rows}"
    }
    check(frame.rows.size == backendState.size.rows) {
        "GPU lab frame is not complete for state geometry"
    }
    check(frame.rows.all { it.columns == backendState.size.columns }) {
        "GPU lab frame contains a row with stale geometry"
    }
}

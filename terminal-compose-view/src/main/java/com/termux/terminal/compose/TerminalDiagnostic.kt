package com.termux.terminal.compose

/** Diagnostic event reported through [TerminalCanvasConfig.onDiagnostics]. */
sealed interface TerminalDiagnostic {
    /** A recoverable backend error (for example, resize or command failure). */
    data class BackendError(val code: Int, val message: String) : TerminalDiagnostic
}

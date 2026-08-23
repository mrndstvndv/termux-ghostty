package com.termux.terminal.compose

/**
 * Java-friendly input seam for controls outside [TerminalCanvas], such as an
 * extra-keys toolbar. Implementations translate these neutral operations into
 * [TerminalCommand]s; they must not reach into native terminal state directly.
 */
interface TerminalInputSink {
    fun submitKey(keyCode: Int, metaState: Int): Boolean

    fun submitCodePoint(codePoint: Int, controlDown: Boolean, alt: Boolean): Boolean

    fun submitText(text: String): Boolean
}

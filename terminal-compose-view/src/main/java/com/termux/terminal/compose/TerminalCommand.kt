package com.termux.terminal.compose

/**
 * Neutral input commands the canvas sends to a [TerminalBackend].
 *
 * Android [android.view.KeyEvent] and [android.view.MotionEvent] values are
 * deliberately not part of this contract so that non-View hosts can translate
 * their own event sources. Key commands carry the raw platform key code and
 * meta state; the backend owns escape-sequence translation.
 */
sealed interface TerminalCommand {

    /**
     * Text (code points) to write to the terminal, e.g. from the IME or from
     * pasted content. Control-character mapping (e.g. '\n' to carriage return)
     * is the backend's responsibility.
     */
    data class Text(val text: String) : TerminalCommand

    /**
     * A hardware-style key event. [keyCode] is an
     * [android.view.KeyEvent.KEYCODE_*] value, [metaState] the platform meta
     * state bits, [codePoint] the Unicode code point produced by the key with
     * modifier keys applied (0 when none), and [combiningAccent] a
     * [android.view.KeyCharacterMap.COMBINING_ACCENT_MASK] value when the key
     * produced a dead key (0 otherwise).
     */
    data class Key(
        val keyCode: Int,
        val metaState: Int,
        val down: Boolean,
        val codePoint: Int = 0,
        val combiningAccent: Int = 0
    ) : TerminalCommand

    /** A pointer/mouse event, e.g. for terminal mouse tracking protocols. */
    data class Mouse(val event: TerminalPointerEvent) : TerminalCommand

    /**
     * Scrolls the terminal viewport by a number of rows; negative values scroll
     * up (toward the transcript), positive values scroll down.
     *
     * The position is the touch location in viewport pixels. Backends that
     * forward scrolls to a mouse-tracking terminal use it to select the pane.
     */
    data class Scroll(
        val rowsDown: Int,
        val xPx: Float,
        val yPx: Float
    ) : TerminalCommand

    /** Jumps the viewport to the given absolute top row. */
    data class SetViewportTopRow(val topRow: Int) : TerminalCommand
}

/**
 * Result of submitting a [TerminalCommand]. Commands are best-effort: a
 * backend without mouse tracking support reports [Unsupported] instead of
 * failing the whole canvas.
 */
sealed interface TerminalCommandResult {
    /** The command was accepted. */
    data object Success : TerminalCommandResult

    /** The backend does not support this command (e.g. mouse protocol off). */
    data class Unsupported(val reason: String) : TerminalCommandResult

    /** The command could not be delivered. */
    data class Failure(val reason: String) : TerminalCommandResult
}

package com.termux.terminal.compose

import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.ui.graphics.Color

/**
 * Consumer-controlled rendering and input policy for [TerminalCanvas].
 *
 * The canvas never reads preferences, storage, or settings here; the consumer
 * owns persistence and policy. [onFontSizeChange], [onSingleTap], [onOpenUrl],
 * [onCopyRequest], [onPasteRequest], [onMoreSelectionRequest], and
 * [onDiagnostics] are callbacks so
 * clipboard, URL handling, and diagnostics policy stay app-owned.
 */
data class TerminalCanvasConfig(
    val fontSize: Int = 14,
    val minimumFontSize: Int = 8,
    val maximumFontSize: Int = 256,
    val typeface: Typeface? = null,
    val cursorEffect: CursorEffect? = null,
    val scrollbar: TerminalScrollbarConfig = TerminalScrollbarConfig(),
    /** Neutral frame-rate request in frames per second; null means display rate. */
    val preferredFrameRate: Float? = null,
    val unconditionalKeyboardOnTap: Boolean = true,
    /** Master switch for opening the soft keyboard on terminal tap. */
    val autoShowKeyboardOnTap: Boolean = true,
    val accessibilityEnabled: Boolean = false,
    /** Color for the selection handles; unspecified uses the host theme accent. */
    val selectionHandleColor: Color = Color.Unspecified,
    /** Increment to clear the current selection after a consumer action. */
    val selectionResetKey: Long = 0L,
    val onFontSizeChange: (Int) -> Unit = {},
    /** Gives a host first chance to apply app-specific tap and keyboard policy. */
    val onSingleTap: (MotionEvent) -> Boolean = { false },
    val onOpenUrl: (String) -> Unit = {},
    val onSelectionChanged: (TerminalSelectionInfo?) -> Unit = {},
    val onCopyRequest: (String) -> Unit = {},
    val onPasteRequest: () -> Unit = {},
    /** Gives a host a chance to consume a hardware key before generic translation. */
    val onKeyDown: (KeyEvent) -> Boolean = { false },
    /** Gives a host a chance to consume a hardware key release. */
    val onKeyUp: (KeyEvent) -> Boolean = { false },
    /** Gives a host a chance to handle an unencoded Unicode code point. */
    val onCodePoint: ((codePoint: Int, controlDown: Boolean, altDown: Boolean) -> Boolean)? = null,
    /** Notifies a host that the platform IME session has closed. */
    val onImeSessionClosed: () -> Unit = {},
    /** Reports the actual platform IME visibility, including an initial value after attach. */
    val onImeVisibilityChanged: (Boolean) -> Unit = {},
    /** Adds the platform's optional More action to the floating toolbar. */
    val onMoreSelectionRequest: ((String) -> Unit)? = null,
    val onDiagnostics: (TerminalDiagnostic) -> Unit = {}
) {
    init {
        require(minimumFontSize >= 1) { "minimumFontSize must be positive" }
        require(maximumFontSize >= minimumFontSize) { "maximumFontSize must be >= minimumFontSize" }
        require(fontSize in minimumFontSize..maximumFontSize) {
            "fontSize must be within minimum..maximum"
        }
    }

    /** Clamps a requested font size to the configured bounds. */
    fun clampedFontSize(requested: Int): Int = requested.coerceIn(minimumFontSize, maximumFontSize)
}

/**
 * Reads sticky modifier keys held by the consumer (e.g. an extra-keys toolbar).
 * The canvas consults this reader while translating key and pointer input.
 */
interface ModifierKeyReader {
    fun readControl(): Boolean
    fun readAlt(): Boolean
    fun readShift(): Boolean
    fun readFn(): Boolean

    /** Completes one-shot modifier consumption after an input dispatch. */
    fun clearConsumedModifiers() = Unit

    companion object {
        val NONE = object : ModifierKeyReader {
            override fun readControl() = false
            override fun readAlt() = false
            override fun readShift() = false
            override fun readFn() = false
        }
    }
}

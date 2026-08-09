package com.termux.terminal.compose

/** Event-time viewport geometry required for terminal pointer encoding. */
data class TerminalPointerGeometry(
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val contentTopPx: Float,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int
)

/**
 * Pointer event reported to the backend for mouse-protocol translation.
 *
 * Positions are viewport pixels relative to the top-left of the terminal
 * canvas. The backend converts them to cells using the same metrics the canvas
 * uses (see [TerminalMetrics]); [cellWidthPx] and [cellHeightPx] are carried so
 * the backend does not need to own a metrics instance.
 */
data class TerminalPointerEvent(
    val action: Action,
    val button: Int,
    val xPx: Float,
    val yPx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val lineSpacingAndAscentPx: Float,
    val viewportWidthPx: Int,
    val viewportHeightPx: Int,
    val modifiers: Int = 0
) {
    enum class Action {
        PRESS,
        RELEASE,
        MOTION
    }

    companion object {
        const val BUTTON_LEFT = 0
        const val BUTTON_RIGHT = 1
        const val BUTTON_MIDDLE = 2
        const val BUTTON_PRIMARY = 1 shl 0
        const val BUTTON_SECONDARY = 1 shl 1
        const val BUTTON_TERTIARY = 1 shl 2

        const val MODIFIER_SHIFT = 1 shl 0
        const val MODIFIER_ALT = 1 shl 1
        const val MODIFIER_CTRL = 1 shl 2
        const val MODIFIER_META = 1 shl 3
    }
}

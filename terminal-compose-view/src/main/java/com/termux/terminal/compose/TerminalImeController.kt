package com.termux.terminal.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Platform IME visibility as observed by the terminal canvas.
 *
 * [UNKNOWN] is the state before the first window-insets observation. A host
 * must not treat it as a user preference.
 */
enum class TerminalImeVisibility {
    UNKNOWN,
    VISIBLE,
    HIDDEN
}

/** A one-shot request sent to the Compose-owned platform input session. */
internal data class TerminalImeRequest(
    val sequence: Long,
    val action: TerminalImeAction
)

internal enum class TerminalImeAction {
    SHOW,
    HIDE
}

/**
 * Coordinates platform IME requests for one [TerminalCanvas].
 *
 * The controller is a Compose state holder and should be created with
 * `remember` by a host that needs to issue requests outside the canvas. It
 * retains the latest request until a canvas consumes it, but does not replay
 * consumed requests after a later attach. It deliberately does not persist
 * state or decide when a terminal is active;
 * those are consumer lifecycle and preference policies.
 */
class TerminalImeController {
    private var nextRequestSequence = 0L
    private var requestState by mutableStateOf<TerminalImeRequest?>(null)
    private var visibilityState by mutableStateOf(TerminalImeVisibility.UNKNOWN)

    /** The latest visibility reported by the platform window insets. */
    val visibility: TerminalImeVisibility
        get() = visibilityState

    /** Requests the canvas to focus its input target and show the IME. */
    fun show() {
        enqueue(TerminalImeAction.SHOW)
    }

    /** Requests the canvas to close its input session and hide the IME. */
    fun hide() {
        enqueue(TerminalImeAction.HIDE)
    }

    /** Requests the opposite of the latest observed platform visibility. */
    fun toggle() {
        if (visibility == TerminalImeVisibility.VISIBLE) {
            hide()
            return
        }
        show()
    }

    /** Toggles from a caller's authoritative visibility observation. */
    fun toggle(isVisible: Boolean) {
        if (isVisible) {
            hide()
            return
        }
        show()
    }

    internal val pendingRequest: TerminalImeRequest?
        get() = requestState

    internal fun consumeRequest(sequence: Long) {
        if (requestState?.sequence == sequence) {
            requestState = null
        }
    }

    internal fun updateVisibility(isVisible: Boolean) {
        visibilityState = if (isVisible) {
            TerminalImeVisibility.VISIBLE
        } else {
            TerminalImeVisibility.HIDDEN
        }
    }

    internal fun onCanvasAttached() {
        visibilityState = TerminalImeVisibility.UNKNOWN
    }

    internal fun onCanvasDetached() {
        visibilityState = TerminalImeVisibility.UNKNOWN
    }

    private fun enqueue(action: TerminalImeAction) {
        nextRequestSequence++
        requestState = TerminalImeRequest(nextRequestSequence, action)
    }
}

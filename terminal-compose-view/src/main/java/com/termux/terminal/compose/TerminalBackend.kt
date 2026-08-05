package com.termux.terminal.compose

/**
 * Backend contract for a terminal [TerminalCanvas].
 *
 * The backend owns the terminal session (a Termux session, a Ghostty session,
 * or a fake) and publishes immutable [TerminalFrame] snapshots. The canvas owns
 * only UI/render resources and never calls back into the session while drawing:
 * a frame is consumed for the duration of one draw and then discarded.
 *
 * All calls on this interface and its listener are main-thread confined unless
 * documented otherwise.
 */
interface TerminalBackend {

    /**
     * Attaches this backend to the canvas. Exactly one listener is active at a
     * time; attaching again replaces the previous listener. The backend should
     * publish a frame as soon as one is available after attach.
     */
    fun attach(listener: TerminalBackendListener)

    /** Detaches the current listener. Frame invalidation stops after this. */
    fun detach()

    /**
     * Resizes the terminal to the given viewport size in pixels. The backend
     * may debounce or clamp; the canvas must keep calling it with the latest
     * size on configuration changes.
     */
    fun resize(widthPx: Int, heightPx: Int)

    /**
     * Submits an input or navigation command. Returns a result; a failed
     * command must never leave the backend in a stuck state.
     */
    fun submit(command: TerminalCommand): TerminalCommandResult

    /**
     * Returns the latest immutable frame snapshot, or null before the first
     * frame is available. The returned frame stays valid for the duration of
     * the caller's draw; the backend must not mutate a published frame.
     */
    fun currentFrame(): TerminalFrame?

    /**
     * Extracts the complete selected text, including transcript rows that are
     * outside the currently visible frame. Backends without transcript access
     * may use the visible-frame fallback.
     */
    fun selectedText(selection: TerminalSelection): String =
        currentFrame()?.selectionText(selection).orEmpty()

    /**
     * Releases all backend-owned resources. After release, every method
     * returns a no-op or failure result and [currentFrame] returns null.
     * Releasing is idempotent.
     */
    fun release()
}

/** Callback interface for a [TerminalBackend]. */
interface TerminalBackendListener {

    /**
     * Invoked when the backend has a new frame to draw. The canvas coalesces
     * rapid invalidations into a single redraw.
     */
    fun onFrameInvalidated()

    /** Invoked when the backend reports a recoverable error. */
    fun onBackendError(error: TerminalBackendError)
}

/** A recoverable backend error, reported through [TerminalBackendListener]. */
class TerminalBackendError(
    val code: Int,
    val message: String
) {
    companion object {
        const val CODE_UNKNOWN = 0
        const val CODE_NO_BACKEND = 1
        const val CODE_RESIZE_FAILED = 2
        const val CODE_COMMAND_FAILED = 3
    }
}

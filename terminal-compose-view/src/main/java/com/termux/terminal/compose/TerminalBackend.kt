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
     * Applies the latest producer publication. Hosts call this when their
     * terminal-session callback fires and when returning to the foreground.
     */
    fun refresh()

    /**
     * Resizes the terminal to the canvas-derived viewport geometry. The
     * backend may debounce; the canvas supplies the exact grid and cell size
     * used for drawing so no hidden renderer needs to measure it again.
     */
    fun resize(size: TerminalSize)

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

/** Complete terminal viewport geometry derived by the canvas renderer. */
data class TerminalSize(
    val widthPx: Int,
    val heightPx: Int,
    val columns: Int,
    val rows: Int,
    val cellWidthPx: Int,
    val cellHeightPx: Int,
    val contentTopPx: Int
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Viewport pixels must be positive" }
        require(columns > 0 && rows > 0) { "Terminal grid must be positive" }
        require(cellWidthPx > 0 && cellHeightPx > 0) { "Cell pixels must be positive" }
        require(contentTopPx >= 0) { "Content top must not be negative" }
    }
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

package com.mrndtvndv.term.ui.workspace

import android.os.Handler
import android.os.Looper
import com.termux.terminal.FrameDelta
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendError
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalSelection
import com.termux.terminal.compose.TerminalSize

/** Default IME resize debounce in milliseconds (0 = resize immediately). */
private const val DefaultResizeDebounceMillis = 0L

/**
 * Session-native adapter for the backend-neutral compose terminal API.
 *
 * The backend applies worker deltas directly into an immutable frame store. It
 * owns viewport navigation and resize translation, so no Android View or
 * legacy renderer participates in publication, caching, or input.
 */
internal class TerminalSessionBackend(
    private val session: TerminalSession,
    resizeDebounceMillis: Long = DefaultResizeDebounceMillis
) : TerminalBackend {

    private val frameStore = TerminalSessionFrameStore()
    private val commandAdapter = TerminalSessionCommandAdapter(
        session = session,
        updateTopRow = ::updateTopRow
    )
    private val resizeHandler = Handler(Looper.getMainLooper())
    private val resizeRunnable = Runnable { applyPendingResize() }
    private var pendingResize: TerminalSize? = null
    private var resizeDebounceMillis = resizeDebounceMillis.coerceAtLeast(0L)
    private var listener: TerminalBackendListener? = null
    private var topRow = 0
    private var fullRefreshRequestedForSequence = -1L
    private var released = false

    fun setResizeDebounceMillis(millis: Long) {
        if (released) return
        resizeDebounceMillis = millis.coerceAtLeast(0L)
    }

    override fun attach(listener: TerminalBackendListener) {
        if (released) return
        this.listener = listener
        val previousSequence = currentFrame()?.sequence
        refresh()
        val currentFrame = currentFrame()
        if (currentFrame != null && currentFrame.sequence == previousSequence) {
            listener.onFrameInvalidated()
        }
    }

    override fun detach() {
        listener = null
    }

    override fun refresh() {
        if (released || !session.hasActiveTerminalBackend()) return
        val frameDelta = session.ghosttyPublishedFrameDelta
        if (frameDelta == null) {
            session.requestGhosttyFullSnapshotRefresh()
            return
        }
        synchronizeViewport(frameDelta)
        try {
            when (frameStore.apply(frameDelta, session.frameState())) {
                TerminalSessionFrameStore.ApplyResult.UPDATED -> {
                    if (frameDelta.isFullRebuild) fullRefreshRequestedForSequence = -1L
                    listener?.onFrameInvalidated()
                }
                TerminalSessionFrameStore.ApplyResult.NEEDS_FULL_REFRESH ->
                    requestFullRefresh(frameDelta.frameSequence)
                TerminalSessionFrameStore.ApplyResult.IGNORED -> Unit
            }
        } catch (error: IllegalArgumentException) {
            recoverFromFrameError(frameDelta.frameSequence, error)
        } catch (error: IllegalStateException) {
            recoverFromFrameError(frameDelta.frameSequence, error)
        }
    }

    override fun resize(size: TerminalSize) {
        if (released) return
        pendingResize = size
        resizeHandler.removeCallbacks(resizeRunnable)
        if (resizeDebounceMillis == 0L) {
            applyPendingResize()
        } else {
            resizeHandler.postDelayed(resizeRunnable, resizeDebounceMillis)
        }
    }

    override fun submit(command: TerminalCommand): TerminalCommandResult {
        if (released || !session.hasActiveTerminalBackend()) {
            return TerminalCommandResult.Failure("Terminal backend is unavailable")
        }

        return try {
            commandAdapter.submit(command)
        } catch (error: IllegalArgumentException) {
            reportError(TerminalBackendError.CODE_COMMAND_FAILED, error.message ?: "Command failed")
            TerminalCommandResult.Failure(error.message ?: "Command failed")
        } catch (error: IllegalStateException) {
            reportError(TerminalBackendError.CODE_COMMAND_FAILED, error.message ?: "Command failed")
            TerminalCommandResult.Failure(error.message ?: "Command failed")
        }
    }

    override fun currentFrame(): TerminalFrame? = if (released) null else frameStore.currentFrame()

    override fun selectedText(selection: TerminalSelection): String {
        if (released || selection.isEmpty) return ""
        return session.terminalContent
            ?.getSelectedText(
                selection.startCol,
                selection.startRow,
                selection.endCol,
                selection.endRow
            )
            .orEmpty()
    }

    override fun release() {
        if (released) return
        released = true
        listener = null
        resizeHandler.removeCallbacks(resizeRunnable)
        pendingResize = null
        frameStore.clear()
    }

    private fun applyPendingResize() {
        val size = pendingResize ?: return
        pendingResize = null
        if (released) return
        session.updateSize(size.columns, size.rows, size.cellWidthPx, size.cellHeightPx)
        topRow = 0
    }

    private fun synchronizeViewport(frameDelta: FrameDelta) {
        val transcriptRows = session.activeTranscriptRows
        topRow = resolveTopRow(
            previousTopRow = topRow,
            viewportChanged = frameDelta.reasonFlags and FrameDelta.REASON_VIEWPORT_SCROLL != 0,
            frameTopRow = frameDelta.topRow,
            autoScrollDisabled = session.isAutoScrollDisabled,
            transcriptRows = transcriptRows,
            rowShift = session.scrollCounter
        )
        session.clearScrollCounter()
        session.setGhosttyTopRow(topRow)
    }

    private fun updateTopRow(requestedTopRow: Int) {
        val nextTopRow = requestedTopRow.coerceIn(-session.activeTranscriptRows, 0)
        if (nextTopRow == topRow) return
        topRow = nextTopRow
        session.setGhosttyTopRow(topRow)
    }

    private fun requestFullRefresh(frameSequence: Long) {
        if (frameSequence <= fullRefreshRequestedForSequence) return
        fullRefreshRequestedForSequence = frameSequence
        session.requestGhosttyFullSnapshotRefresh()
    }

    private fun recoverFromFrameError(frameSequence: Long, error: RuntimeException) {
        reportError(TerminalBackendError.CODE_UNKNOWN, error.message ?: "Could not apply terminal frame")
        requestFullRefresh(frameSequence)
    }

    private fun TerminalSession.frameState(): TerminalFrameSessionState =
        TerminalFrameSessionState(
            transcriptRows = activeTranscriptRows,
            cursorBlinkingEnabled = isGhosttyCursorBlinkingEnabled,
            cursorBlinkState = ghosttyCursorBlinkState
        )

    private fun reportError(code: Int, message: String) {
        listener?.onBackendError(TerminalBackendError(code, message))
    }
}

/**
 * Resolves the viewport top row for an applied frame.
 *
 * Streaming appends must not yank the user back to the bottom while they are
 * reading scrollback: a viewport above the bottom edge is shifted by the rows
 * appended since the last applied frame so the visible content stays put. Only
 * a viewport at the very bottom snaps back to follow new output.
 */
internal fun resolveTopRow(
    previousTopRow: Int,
    viewportChanged: Boolean,
    frameTopRow: Int,
    autoScrollDisabled: Boolean,
    transcriptRows: Int,
    rowShift: Int
): Int = when {
    viewportChanged -> frameTopRow
    previousTopRow < 0 || autoScrollDisabled -> shiftedTopRow(previousTopRow, transcriptRows, rowShift)
    else -> 0
}.coerceIn(-transcriptRows, 0)

private fun shiftedTopRow(previousTopRow: Int, transcriptRows: Int, rowShift: Int): Int {
    if (-previousTopRow + rowShift > transcriptRows) return -transcriptRows
    return previousTopRow - rowShift
}

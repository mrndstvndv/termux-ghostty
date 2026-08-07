package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Typeface
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalBackendError
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalSelection

/** Default IME resize debounce in milliseconds (0 = resize immediately). */
private const val DefaultResizeDebounceMillis = 0L

/**
 * App-owned adapter from the existing Ghostty session/view cache to the
 * backend-neutral compose terminal API.
 *
 * The hidden [ComposeInputTerminalView] remains a frame-cache adapter while the
 * session-native backend is migrated. It is never used for drawing or input
 * dispatch; the library owns both of those paths.
 */
internal class TerminalSessionBackend(
    context: Context,
    private val session: TerminalSession,
    private val extraKeysController: ExtraKeysController,
    fontSize: Int,
    private val terminalTypeface: Typeface,
    resizeDebounceMillis: Long = DefaultResizeDebounceMillis
) : TerminalBackend {

    val view: ComposeInputTerminalView = ComposeInputTerminalView(context).apply {
        isFocusable = true
        isFocusableInTouchMode = true
        setTextSize(fontSize)
        setTypeface(terminalTypeface)
        this.resizeDebounceMillis = resizeDebounceMillis
        setTerminalViewClient(createViewClient())
    }

    private val frameAdapter = TerminalSessionFrameAdapter(session, view)
    private val frameCache = LazyTerminalFrameCache(frameAdapter::build)
    private val commandAdapter = TerminalSessionCommandAdapter(session, view)
    private var listener: TerminalBackendListener? = null
    private var released = false

    init {
        view.onInvalidateCallback = ::onViewInvalidated
        view.attachSession(session)
    }

    fun setFontSize(fontSize: Int) {
        if (released || view.mRenderer?.mTextSize == fontSize) return
        view.setTextSize(fontSize)
        view.invalidate()
    }

    fun setResizeDebounceMillis(millis: Long) {
        if (released) return
        view.resizeDebounceMillis = millis
    }

    override fun attach(listener: TerminalBackendListener) {
        if (released) return
        this.listener = listener
        if (currentFrame() != null) listener.onFrameInvalidated()
    }

    override fun detach() {
        listener = null
    }

    override fun resize(widthPx: Int, heightPx: Int) {
        if (released || widthPx <= 0 || heightPx <= 0) return
        view.layout(0, 0, widthPx, heightPx)
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

    override fun currentFrame(): TerminalFrame? {
        if (released) return null

        return try {
            frameCache.currentFrame()
        } catch (error: IllegalArgumentException) {
            reportError(TerminalBackendError.CODE_UNKNOWN, error.message ?: "Could not read terminal frame")
            frameCache.cachedFrame()
        } catch (error: IllegalStateException) {
            reportError(TerminalBackendError.CODE_UNKNOWN, error.message ?: "Could not read terminal frame")
            frameCache.cachedFrame()
        }
    }

    override fun selectedText(selection: TerminalSelection): String {
        if (released || selection.isEmpty) return ""
        return session.getTerminalContent()
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
        view.onInvalidateCallback = null
        view.cancelPendingResize()
        view.attachSession(null)
        frameCache.clear()
    }

    private fun createViewClient(): TermuxTerminalViewClientBase =
        object : TermuxTerminalViewClientBase() {
            override fun shouldOpenTerminalTranscriptURLOnClick(): Boolean = true

            override fun readControlKey(): Boolean = extraKeysController.readControl()

            override fun readAltKey(): Boolean = extraKeysController.readAlt()

            override fun readShiftKey(): Boolean = extraKeysController.readShift()

            override fun readFnKey(): Boolean = extraKeysController.readFn()
        }

    private fun onViewInvalidated() {
        if (released) return
        frameCache.invalidate()
        listener?.onFrameInvalidated()
    }

    private fun reportError(code: Int, message: String) {
        listener?.onBackendError(TerminalBackendError(code, message))
    }
}

package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.view.TerminalView

/**
 * TEMPORARY migration adapter (plan stage 5) — DO NOT extend.
 *
 * Bridges the Ghostty session's existing [TerminalView] frame cache to
 * [TerminalSessionBackend] until the session-native backend supplies frames,
 * metrics, links, resize, scroll, mouse, and input itself.
 *
 * REMOVAL TICKET (plan stage 8): delete this class and [TerminalSessionBackend]
 * once no core or compose-app extraction path references `TerminalView`,
 * `TerminalRenderer`, `TerminalViewLinkLayout`, or `TermuxTerminalViewClientBase`.
 */
class ComposeInputTerminalView(context: Context) : TerminalView(context, null) {
    var onInvalidateCallback: (() -> Unit)? = null

    /**
     * Debounce for IME-inset-driven resize, in milliseconds. The soft-keyboard
     * opening animation produces a stream of intermediate heights; this
     * coalesces them so Ghostty/tmux is reflowed once at the settled height.
     * 0 applies the newest size immediately. Defaults to 0 (applied immediately).
     */
    var resizeDebounceMillis: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    private val resizeHandler = Handler(Looper.getMainLooper())
    private val resizeRunnable = Runnable { updateSize() }

    init {
        setWillNotDraw(true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        // TerminalCanvas owns layout because this view is not attached as a Compose AndroidView.
        // Debounce IME insets animation so every intermediate height does not resize Ghostty.
        resizeHandler.removeCallbacks(resizeRunnable)
        if (w > 0 && h > 0) {
            resizeHandler.postDelayed(resizeRunnable, resizeDebounceMillis)
        }
    }

    fun cancelPendingResize() {
        resizeHandler.removeCallbacks(resizeRunnable)
    }

    override fun onFrameAvailable() {
        // TerminalView.applyScreenUpdate() calls invalidate(), which is overridden below.
        // Calling the callback here as well double-counts every Ghostty frame.
        super.onFrameAvailable()
    }

    override fun invalidate() {
        super.invalidate()
        onInvalidateCallback?.invoke()
    }
}

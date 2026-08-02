package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.termux.view.TerminalView

private const val ResizeDebounceMillis = 10L

class ComposeInputTerminalView(context: Context) : TerminalView(context, null) {
    var onInvalidateCallback: (() -> Unit)? = null

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
            resizeHandler.postDelayed(resizeRunnable, ResizeDebounceMillis)
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

package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Canvas
import com.termux.view.TerminalView

class ComposeInputTerminalView(context: Context) : TerminalView(context, null) {
    var onInvalidateCallback: (() -> Unit)? = null

    init {
        setWillNotDraw(true)
    }

    override fun onFrameAvailable() {
        super.onFrameAvailable()
        onInvalidateCallback?.invoke()
    }

    override fun invalidate() {
        super.invalidate()
        onInvalidateCallback?.invoke()
    }
}

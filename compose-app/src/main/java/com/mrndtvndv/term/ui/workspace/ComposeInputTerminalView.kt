package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Canvas
import com.termux.view.TerminalView

class ComposeInputTerminalView(context: Context) : TerminalView(context, null) {
    override fun onDraw(canvas: Canvas) {
        // Do not perform standard View rendering to avoid the slow hybrid rendering loop.
        // We only let Compose Canvas handle the drawing.
    }
}

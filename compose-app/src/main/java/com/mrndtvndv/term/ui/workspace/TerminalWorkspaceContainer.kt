package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase

import android.view.MotionEvent
import android.view.ViewGroup
import com.termux.shared.view.KeyboardUtils

fun TerminalView.detachSession() {
    attachSession(null)
}

@Composable
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            var currentFontSize = 14
            TerminalView(context, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(currentFontSize)
                setTerminalViewClient(object : TermuxTerminalViewClientBase() {
                    override fun onSingleTapUp(e: MotionEvent) {
                        this@apply.requestFocus()
                        KeyboardUtils.showSoftKeyboard(context, this@apply)
                    }

                    override fun onScale(scale: Float): Float {
                        if (scale < 0.9f || scale > 1.1f) {
                            val increase = scale > 1.0f
                            val delta = if (increase) 1 else -1
                            val newSize = (currentFontSize + delta).coerceIn(4, 40)
                            if (newSize != currentFontSize) {
                                currentFontSize = newSize
                                this@apply.setTextSize(newSize)
                            }
                            return 1.0f
                        }
                        return scale
                    }
                })
                attachSession(session)
                onViewCreated(this)
            }
        },
        update = { view ->
            if (view.mTermSession != session) {
                view.attachSession(session)
            }
            onViewCreated(view)
            // Force immediate resize so the terminal session knows the real dimensions
            view.post { view.updateSize(true) }
        },
        onRelease = { view ->
            view.detachSession()
            onViewReleased()
        },
        modifier = modifier.fillMaxSize()
    )
}

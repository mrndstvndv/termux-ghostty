package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase

import android.view.MotionEvent
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
            TerminalView(context, null).apply {
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(14)
                setTerminalViewClient(object : TermuxTerminalViewClientBase() {
                    override fun onSingleTapUp(e: MotionEvent) {
                        this@apply.requestFocus()
                        KeyboardUtils.showSoftKeyboard(context, this@apply)
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
        },
        onRelease = { view ->
            view.detachSession()
            onViewReleased()
        },
        modifier = modifier.fillMaxSize()
    )
}

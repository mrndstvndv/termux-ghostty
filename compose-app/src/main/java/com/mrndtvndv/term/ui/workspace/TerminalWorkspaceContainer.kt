package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.shared.interact.ShareUtils

import android.content.Context
import android.graphics.Typeface
import java.io.File
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.termux.shared.view.KeyboardUtils

import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint

fun TerminalView.detachSession() {
    attachSession(null)
}

private fun TerminalView.forceUpdateSize() {
    updateSize(true)
}

@Composable
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TerminalCanvas(
        session = session,
        onOpenUrl = onOpenUrl,
        onViewCreated = onViewCreated,
        onViewReleased = onViewReleased,
        modifier = modifier
    )
}

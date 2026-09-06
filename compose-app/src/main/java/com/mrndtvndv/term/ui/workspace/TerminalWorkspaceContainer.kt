package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend

@Composable
@Suppress("LongParameterList")
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onUploadImage: () -> Unit,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    isTerminalActive: Boolean,
    onKeyboardVisibilityChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    TerminalCanvas(
        session = session,
        extraKeysController = extraKeysController,
        onUploadImage = onUploadImage,
        onOpenUrl = onOpenUrl,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        isTerminalActive = isTerminalActive,
        onKeyboardVisibilityChanged = onKeyboardVisibilityChanged,
        modifier = modifier
    )
}

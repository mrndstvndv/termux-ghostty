package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalImeController

@Composable
@Suppress("LongParameterList")
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    isTerminalActive: Boolean,
    imeController: TerminalImeController,
    modifier: Modifier = Modifier
) {
    TerminalCanvas(
        session = session,
        extraKeysController = extraKeysController,
        onUploadMedia = onUploadMedia,
        onUploadFile = onUploadFile,
        onOpenUrl = onOpenUrl,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        isTerminalActive = isTerminalActive,
        imeController = imeController,
        modifier = modifier
    )
}

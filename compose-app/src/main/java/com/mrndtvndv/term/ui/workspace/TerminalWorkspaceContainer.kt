package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend

@Composable
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    isTerminalActive: Boolean,
    modifier: Modifier = Modifier
) {
    TerminalCanvas(
        session = session,
        extraKeysController = extraKeysController,
        onOpenUrl = onOpenUrl,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        isTerminalActive = isTerminalActive,
        modifier = modifier
    )
}

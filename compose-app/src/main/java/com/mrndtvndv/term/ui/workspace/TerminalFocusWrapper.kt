package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend

/**
 * Compatibility boundary for the workspace pager. Focus and key dispatch are
 * now owned by the reusable terminal canvas.
 */
@Composable
fun TerminalFocusWrapper(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    isTerminalActive: Boolean,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TerminalWorkspaceContainer(
        session = session,
        extraKeysController = extraKeysController,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        onOpenUrl = onOpenUrl,
        isTerminalActive = isTerminalActive,
        modifier = modifier
    )
}

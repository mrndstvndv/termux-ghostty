package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalImeController

/**
 * Compatibility boundary for the workspace pager. Focus and key dispatch are
 * now owned by the reusable terminal canvas.
 */
@Composable
@Suppress("LongParameterList")
fun TerminalFocusWrapper(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onUploadImage: () -> Unit,
    isTerminalActive: Boolean,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    imeController: TerminalImeController,
    modifier: Modifier = Modifier
) {
    TerminalWorkspaceContainer(
        session = session,
        extraKeysController = extraKeysController,
        onUploadImage = onUploadImage,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        onOpenUrl = onOpenUrl,
        imeController = imeController,
        isTerminalActive = isTerminalActive,
        modifier = modifier
    )
}

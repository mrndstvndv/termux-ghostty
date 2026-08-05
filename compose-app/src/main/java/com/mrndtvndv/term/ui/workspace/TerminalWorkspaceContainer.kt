package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView

@Composable
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    onOpenUrl: (String) -> Unit,
    isTerminalActive: Boolean,
    modifier: Modifier = Modifier
) {
    TerminalCanvas(
        session = session,
        extraKeysController = extraKeysController,
        onOpenUrl = onOpenUrl,
        onViewCreated = onViewCreated,
        onViewReleased = onViewReleased,
        isTerminalActive = isTerminalActive,
        modifier = modifier
    )
}

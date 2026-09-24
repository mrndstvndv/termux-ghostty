package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalBackend
import com.termux.terminal.compose.TerminalImeController
import com.termux.terminal.compose.TerminalWallpaperConfig

/**
 * Compatibility boundary for the workspace pager. Focus and key dispatch are
 * now owned by the reusable terminal canvas.
 */
@Composable
@Suppress("LongParameterList")
fun TerminalFocusWrapper(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    onCommitContent: (ClipData) -> Boolean = { false },
    isTerminalActive: Boolean,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    onOpenUrl: (String) -> Unit,
    imeController: TerminalImeController,
    wallpaperConfig: TerminalWallpaperConfig = TerminalWallpaperConfig(),
    modifier: Modifier = Modifier
) {
    TerminalWorkspaceContainer(
        session = session,
        extraKeysController = extraKeysController,
        onUploadMedia = onUploadMedia,
        onUploadFile = onUploadFile,
        onCommitContent = onCommitContent,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        onOpenUrl = onOpenUrl,
        imeController = imeController,
        isTerminalActive = isTerminalActive,
        wallpaperConfig = wallpaperConfig,
        modifier = modifier
    )
}

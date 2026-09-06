package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.ui.keyboard.SoftKeyboardState
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.termux.terminal.compose.TerminalBackend
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import com.mrndtvndv.term.server.TerminalProgress
import java.io.File

@Suppress("LongParameterList")
@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    terminalProgress: TerminalProgress?,
    onUploadMedia: () -> Unit,
    onUploadFile: () -> Unit,
    sftpViewModel: SftpViewModel? = null,
    reviewViewModel: ReviewViewModel? = null,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    hideWorkspaceTabs: Boolean = false,
    herdrEnabled: Boolean = false,
    herdrWorkspaces: List<HerdrWorkspaceResolver.HerdrWorkspaceNode> = emptyList(),
    herdrWorkspacesLoading: Boolean = false,
    herdrWorkspacesError: String? = null,
    onLoadHerdrAgents: () -> Unit = {},
    onFocusHerdrTab: (HerdrWorkspaceResolver.HerdrTabNode) -> Unit = {},
    onFocusHerdrPane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit = {},
    onCloseHerdrPane: (HerdrWorkspaceResolver.HerdrPaneNode) -> Unit = {},
    herdrAgentFabOpacity: Float = 0.7f,
    rememberSoftKeyboardState: Boolean = false,
    lastSoftKeyboardState: SoftKeyboardState = SoftKeyboardState.UNKNOWN,
    onSoftKeyboardStateChanged: (Boolean) -> Unit = {},
    showKeyboardFab: Boolean = false,
    hideKeyboardFabWhileTyping: Boolean = true,
    onBackendCreated: (TerminalSession, TerminalBackend) -> Unit,
    onBackendReleased: (TerminalSession, TerminalBackend) -> Unit,
    activeTab: WorkspaceTab,
    onTabSelected: (WorkspaceTab) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extraKeysController = remember { com.mrndtvndv.term.ui.keyboard.ExtraKeysController() }
    TabbedWorkspace(
        session = session,
        terminalProgress = terminalProgress,
        onUploadMedia = onUploadMedia,
        onUploadFile = onUploadFile,
        sftpViewModel = sftpViewModel,
        reviewViewModel = reviewViewModel,
        extraKeysController = extraKeysController,
        extraKeysEnabled = extraKeysEnabled,
        extraKeysJson = extraKeysJson,
        hideTabs = hideWorkspaceTabs,
        herdrEnabled = herdrEnabled,
        herdrWorkspaces = herdrWorkspaces,
        herdrWorkspacesLoading = herdrWorkspacesLoading,
        herdrWorkspacesError = herdrWorkspacesError,
        onLoadHerdrAgents = onLoadHerdrAgents,
        onFocusHerdrTab = onFocusHerdrTab,
        onFocusHerdrPane = onFocusHerdrPane,
        onCloseHerdrPane = onCloseHerdrPane,
        herdrAgentFabOpacity = herdrAgentFabOpacity,
        rememberSoftKeyboardState = rememberSoftKeyboardState,
        lastSoftKeyboardState = lastSoftKeyboardState,
        onSoftKeyboardStateChanged = onSoftKeyboardStateChanged,
        showKeyboardFab = showKeyboardFab,
        hideKeyboardFabWhileTyping = hideKeyboardFabWhileTyping,
        onBackendCreated = onBackendCreated,
        onBackendReleased = onBackendReleased,
        activeTab = activeTab,
        onTabSelected = onTabSelected,
        onOpenFile = onOpenFile,
        onOpenFileError = onOpenFileError,
        onOpenUrl = onOpenUrl,
        onRefreshWorkspace = onRefreshWorkspace,
        modifier = modifier
    )
}

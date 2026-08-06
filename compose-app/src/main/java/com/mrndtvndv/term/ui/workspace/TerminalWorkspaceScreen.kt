package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.server.HerdrWorkspaceResolver
import java.io.File

@Suppress("LongParameterList")
@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel? = null,
    reviewViewModel: ReviewViewModel? = null,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    hideWorkspaceTabs: Boolean = false,
    herdrEnabled: Boolean = false,
    herdrAgents: List<HerdrWorkspaceResolver.HerdrAgentInfo> = emptyList(),
    herdrAgentsLoading: Boolean = false,
    herdrAgentsError: String? = null,
    onLoadHerdrAgents: () -> Unit = {},
    onFocusHerdrAgent: (HerdrWorkspaceResolver.HerdrAgentInfo) -> Unit = {},
    herdrAgentFabOpacity: Float = 0.7f,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    activeTab: WorkspaceTab,
    onTabSelected: (WorkspaceTab) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val extraKeysController = remember { com.mrndtvndv.term.ui.keyboard.ExtraKeysController() }
    val activeTerminalViewRef = remember { arrayOfNulls<TerminalView>(1) }
    val getActiveTerminalView = remember { { activeTerminalViewRef[0] } }

    val handleViewCreated: (TerminalView) -> Unit = { view ->
        activeTerminalViewRef[0] = view
        onViewCreated(view)
    }

    val handleViewReleased: (TerminalView) -> Unit = { view ->
        if (activeTerminalViewRef[0] === view) {
            activeTerminalViewRef[0] = null
        }
        onViewReleased(view)
    }

    TabbedWorkspace(
        session = session,
        sftpViewModel = sftpViewModel,
        reviewViewModel = reviewViewModel,
        extraKeysController = extraKeysController,
        getActiveTerminalView = getActiveTerminalView,
        extraKeysEnabled = extraKeysEnabled,
        extraKeysJson = extraKeysJson,
        hideTabs = hideWorkspaceTabs,
        herdrEnabled = herdrEnabled,
        herdrAgents = herdrAgents,
        herdrAgentsLoading = herdrAgentsLoading,
        herdrAgentsError = herdrAgentsError,
        onLoadHerdrAgents = onLoadHerdrAgents,
        onFocusHerdrAgent = onFocusHerdrAgent,
        herdrAgentFabOpacity = herdrAgentFabOpacity,
        onViewCreated = handleViewCreated,
        onViewReleased = handleViewReleased,
        activeTab = activeTab,
        onTabSelected = onTabSelected,
        onOpenFile = onOpenFile,
        onOpenFileError = onOpenFileError,
        onOpenUrl = onOpenUrl,
        onRefreshWorkspace = onRefreshWorkspace,
        modifier = modifier
    )
}

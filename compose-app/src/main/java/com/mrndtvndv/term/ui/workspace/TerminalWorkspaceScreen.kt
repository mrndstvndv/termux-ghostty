package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.review.ReviewViewModel
import java.io.File

@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel? = null,
    reviewViewModel: ReviewViewModel? = null,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    hideWorkspaceTabs: Boolean = false,
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

package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.review.ReviewViewModel
import kotlinx.coroutines.flow.filterIsInstance
import java.io.File

@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    sftpViewModel: SftpViewModel? = null,
    reviewViewModel: ReviewViewModel? = null,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
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
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isWideScreen = configuration.screenWidthDp >= 600

    val extraKeysController = remember { com.mrndtvndv.term.ui.keyboard.ExtraKeysController() }
    val activeTerminalViewRef = remember { arrayOfNulls<TerminalView>(1) }
    val getActiveTerminalView = remember { { activeTerminalViewRef[0] } }

    var foldingFeature by remember { mutableStateOf<FoldingFeature?>(null) }
    LaunchedEffect(context) {
        val activity = context as? Activity ?: return@LaunchedEffect
        WindowInfoTracker.getOrCreate(context)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                foldingFeature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()
            }
    }

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

    if (isWideScreen) {
        SplitWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            reviewViewModel = reviewViewModel,
            foldingFeature = foldingFeature,
            extraKeysController = extraKeysController,
            getActiveTerminalView = getActiveTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
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
    } else {
        TabbedWorkspace(
            session = session,
            sftpViewModel = sftpViewModel,
            reviewViewModel = reviewViewModel,
            extraKeysController = extraKeysController,
            getActiveTerminalView = getActiveTerminalView,
            extraKeysEnabled = extraKeysEnabled,
            extraKeysJson = extraKeysJson,
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
}

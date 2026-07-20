package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import android.view.ViewGroup
import android.webkit.WebView
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
    isLocal: Boolean = false,
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
    browserUrl: String,
    onBrowserUrlChanged: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val getBrowserWebView: () -> WebView = remember(context) {
        var cachedWebView: WebView? = null
        {
            if (cachedWebView == null) {
                cachedWebView = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    settings.supportZoom()
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                }
            }
            cachedWebView
        }
    }
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
            getWebView = getBrowserWebView,
            session = session,
            isLocal = isLocal,
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
            browserUrl = browserUrl,
            onBrowserUrlChanged = onBrowserUrlChanged,
            onOpenUrl = onOpenUrl,
            onRefreshWorkspace = onRefreshWorkspace,
            modifier = modifier
        )
    } else {
        TabbedWorkspace(
            getWebView = getBrowserWebView,
            session = session,
            isLocal = isLocal,
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
            browserUrl = browserUrl,
            onBrowserUrlChanged = onBrowserUrlChanged,
            onOpenUrl = onOpenUrl,
            onRefreshWorkspace = onRefreshWorkspace,
            modifier = modifier
        )
    }
}

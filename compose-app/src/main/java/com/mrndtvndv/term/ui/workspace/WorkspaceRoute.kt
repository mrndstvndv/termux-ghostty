@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)
@file:Suppress("MatchingDeclarationName")

package com.mrndtvndv.term.ui.workspace

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.data.prefs.AppSettings
import com.mrndtvndv.term.navigation.AppRoute
import com.mrndtvndv.term.navigation.WorkspaceNavigator
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.ui.review.ReviewScreenRoute
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpScreenRoute
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.sftp.openDownloadedFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.termux.terminal.TerminalSession
import java.io.File

class TerminalHostCallbacks(
    val onCommitContent: (TerminalSession, ClipData) -> Boolean,
    val onRequestMediaUpload: (TerminalSession) -> Unit,
    val onRequestFileUpload: (TerminalSession) -> Unit,
    val onOpenUrl: (String) -> Unit,
)

@Composable
fun WorkspaceRoute(
    serverId: String,
    appViewModel: AppViewModel,
    container: AppContainer,
    settings: AppSettings,
    appearance: TerminalAppearance,
    host: TerminalHostCallbacks,
    onBack: () -> Unit,
) {
    val server = appViewModel.getServer(serverId)
    // Closing drops the workspace navigator, but the entry still composes during the exit animation.
    val workspace = remember(serverId) { appViewModel.navigator.workspace(serverId) }
    if (server == null || workspace == null) {
        WorkspaceLoading()
        return
    }

    val sessionManager = container.sessionManager
    val store = appViewModel.connectionStore(serverId)
    val workspaceViewModel = viewModel(viewModelStoreOwner = store) {
        WorkspaceViewModel(serverId, sessionManager)
    }
    val reviewViewModel = if (WorkspaceTab.Review in workspace.tabs) reviewViewModel(server, store) else null
    val sftpViewModel = if (WorkspaceTab.Sftp in workspace.tabs) sftpViewModel(server, store, sessionManager) else null

    val session = server.terminalSession
    val progress by workspaceViewModel.observeTerminalProgress(session).collectAsStateWithLifecycle()
    val herdrState by workspaceViewModel.herdrState.collectAsStateWithLifecycle()
    val herdr = if (server.config.herdrEnabled) workspaceViewModel.herdrBinding(herdrState) else null

    // Without this the root NavDisplay would preview popping the whole workspace
    // when back only switches tabs.
    BackHandler(enabled = workspace.handlesTabSwitchBack, onBack = onBack)

    WorkspacePager(
        tabs = workspace.tabs,
        currentTab = workspace.currentTab,
        onSelectTab = workspace::selectTab,
        hideTabs = settings.hideWorkspaceTabs,
    ) { tab, isActive ->
        when (tab) {
            WorkspaceTab.Terminal -> TerminalPane(
                session = session,
                progress = progress,
                settings = settings,
                appearance = appearance,
                isActive = isActive,
                herdr = herdr,
                callbacks = host.paneCallbacks(session, container.preferences::update),
            )
            WorkspaceTab.Review -> reviewViewModel?.let {
                ReviewScreenRoute(
                    viewModel = it,
                    backStack = workspace.reviewStack,
                    onOpenDiff = workspace::openDiff,
                    onBack = onBack,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            WorkspaceTab.Sftp -> sftpViewModel?.let {
                WorkspaceSftp(
                    sftpViewModel = it,
                    workspace = workspace,
                    isActive = isActive,
                    onBack = onBack,
                    onViewText = { file -> appViewModel.navigator.navigate(AppRoute.FileViewer(file.absolutePath)) },
                    onError = sessionManager::handleTerminalNotification,
                )
            }
        }
    }
}

@Composable
private fun WorkspaceLoading() {
    Box(modifier = Modifier.fillMaxSize()) {
        LoadingIndicator(modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun reviewViewModel(server: Server, store: ViewModelStoreOwner): ReviewViewModel =
    viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        ReviewViewModel(
            execCommand = { command -> sshSession.execCommand(command) },
            workspaceDir = checkNotNull(server.tracker).workspaceDir,
        )
    }

@Composable
private fun sftpViewModel(
    server: Server,
    store: ViewModelStoreOwner,
    sessionManager: AppSessionManagerAccess,
): SftpViewModel =
    viewModel(viewModelStoreOwner = store) {
        val sshSession = checkNotNull(server.sshSession)
        SftpViewModel(
            client = checkNotNull(server.sftpClient),
            execCommand = { command -> sshSession.execCommand(command) },
            transferManager = SftpTransferManager.current,
            ownerKey = server.config.id,
            onDirectoryChanged = { path -> sessionManager.coordinator.onDirectoryChanged(server.config.id, path) },
        )
    }

private fun WorkspaceViewModel.herdrBinding(state: HerdrUiState) = HerdrBinding(
    state = state,
    onRefresh = ::loadHerdrAgents,
    onFocusTab = ::focusTab,
    onFocusPane = ::focusPane,
    onClosePane = ::closePane,
)

private fun TerminalHostCallbacks.paneCallbacks(
    session: TerminalSession,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
) = TerminalPaneCallbacks(
    onUploadMedia = { onRequestMediaUpload(session) },
    onUploadFile = { onRequestFileUpload(session) },
    onCommitContent = { content -> onCommitContent(session, content) },
    onOpenUrl = onOpenUrl,
    onUpdateSettings = onUpdateSettings,
)

@Composable
private fun WorkspaceSftp(
    sftpViewModel: SftpViewModel,
    workspace: WorkspaceNavigator,
    isActive: Boolean,
    onBack: () -> Unit,
    onViewText: (File) -> Unit,
    onError: (title: String, message: String) -> Unit,
) {
    val context = LocalContext.current
    SftpScreenRoute(
        viewModel = sftpViewModel,
        backStack = workspace.sftpStack,
        onOpenFolder = workspace::openFolder,
        onBack = onBack,
        isTabActive = isActive,
        onOpenFile = { file ->
            openDownloadedFile(context = context, file = file, onViewText = onViewText, onError = onError)
        },
        onOpenFileError = { message -> onError("SFTP Error", message) },
    )
}

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui

import android.graphics.Typeface
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface as ComposeTypeface
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.mrndtvndv.term.AppContainer
import com.mrndtvndv.term.AppViewModel
import com.mrndtvndv.term.clipboard.UploadController
import com.mrndtvndv.term.navigation.AppNavDisplay
import com.mrndtvndv.term.navigation.AppRoute
import com.mrndtvndv.term.navigation.LocalAnimationsEnabled
import com.mrndtvndv.term.ui.addserver.AddServerScreen
import com.mrndtvndv.term.ui.notification.InAppNotificationBanner
import com.mrndtvndv.term.ui.serverlist.ServerListScreen
import com.mrndtvndv.term.ui.settings.SettingsRoute
import com.mrndtvndv.term.ui.settings.SettingsViewModel
import com.mrndtvndv.term.ui.sftp.SftpFileViewerScreen
import com.mrndtvndv.term.ui.theme.TerminalThemeSync
import com.mrndtvndv.term.ui.theme.TermuxGhosttyTheme
import com.mrndtvndv.term.ui.workspace.DebugHud
import com.mrndtvndv.term.ui.workspace.FileUploadBlockingOverlay
import com.mrndtvndv.term.ui.workspace.TerminalAppearance
import com.mrndtvndv.term.ui.workspace.TerminalHostCallbacks
import com.mrndtvndv.term.ui.workspace.WallpaperController
import com.mrndtvndv.term.ui.workspace.WorkspaceRoute
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import com.mrndtvndv.term.ui.workspace.rememberTerminalWallpaperConfig
import com.mrndtvndv.term.ui.workspace.retainedPredecodedWallpaper
import com.termux.terminal.TerminalSession
import java.io.File

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@Composable
fun TermApp(
    appViewModel: AppViewModel,
    container: AppContainer,
    uploads: UploadController,
    onActiveTerminalSessionChanged: (TerminalSession?) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    val navigator = appViewModel.navigator
    val serverListState by appViewModel.serverListState.collectAsStateWithLifecycle()
    val preferences = container.preferences
    val settings by preferences.settings.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val onBack = {
        focusManager.clearFocus()
        keyboardController?.hide()
        navigator.goBack()
    }
    val notification by appViewModel.notificationState.notification.collectAsStateWithLifecycle()
    val uploadInProgress by uploads.inProgress.collectAsStateWithLifecycle()
    val wallpaperController = remember { WallpaperController() }
    val customFontFamily = remember(settings.customFontName, settings.useCustomFontForWholeUi) {
        if (settings.useCustomFontForWholeUi && settings.customFontName != null) {
            container.fontStore.loadTypeface()?.let { FontFamily(ComposeTypeface(it)) }
        } else {
            null
        }
    }
    val terminalTypeface = remember(settings.customFontName) {
        container.fontStore.loadTypeface() ?: Typeface.MONOSPACE
    }

    TermuxGhosttyTheme(theme = settings.theme, customFontFamily = customFontFamily) {
        CompositionLocalProvider(LocalAnimationsEnabled provides settings.animationsEnabled) {
            Surface(modifier = Modifier.fillMaxSize()) {
                // Drop the in-memory decoded wallpaper once wallpapering is disabled so its
                // bitmap can be reclaimed. The saved URI/name/id and its persisted grant stay
                // intact, so re-enabling reloads from the saved URI.
                LaunchedEffect(settings.wallpaperEnabled) {
                    if (!settings.wallpaperEnabled) {
                        wallpaperController.predecoded =
                            retainedPredecodedWallpaper(wallpaperController.predecoded, enabled = false)
                    }
                }

                val wallpaperConfig = rememberTerminalWallpaperConfig(
                    uri = settings.wallpaperUri,
                    id = settings.wallpaperId,
                    enabled = settings.wallpaperEnabled,
                    backgroundOpacity = settings.wallpaperOpacity,
                    scaling = settings.wallpaperScaling,
                    predecoded = wallpaperController.predecoded,
                )

                val workspaceId = navigator.currentWorkspaceId
                val currentSession = workspaceId?.let { appViewModel.getServer(it) }?.terminalSession
                val focusedSession = currentSession?.takeIf {
                    navigator.workspace(workspaceId)?.currentTab == WorkspaceTab.Terminal
                }
                LaunchedEffect(focusedSession) {
                    onActiveTerminalSessionChanged(focusedSession)
                }
                TerminalThemeSync(termSession = currentSession, appTheme = settings.theme)

                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavDisplay(
                        backStack = navigator.backStack,
                        onBack = onBack,
                        entryDecorators = listOf(
                            rememberSaveableStateHolderNavEntryDecorator(),
                            rememberViewModelStoreNavEntryDecorator(),
                        ),
                        entryProvider = entryProvider {
                            entry<AppRoute.ServerList> {
                                ServerListScreen(
                                    servers = serverListState.servers,
                                    activeIds = serverListState.activeIds,
                                    disconnectingId = serverListState.disconnectingId,
                                    connectingId = serverListState.connectingId,
                                    onTap = appViewModel::connect,
                                    onDelete = appViewModel::deleteServer,
                                    onEdit = { serverId -> navigator.navigate(AppRoute.EditServer(serverId)) },
                                    onDisconnect = appViewModel::disconnect,
                                    onAdd = { navigator.navigate(AppRoute.AddServer) },
                                    onSettingsClick = { navigator.navigate(AppRoute.Settings) },
                                    onStartLocal = appViewModel::startLocalTerminal,
                                    localConfig = serverListState.localConfig,
                                    onSetStartupCommand = appViewModel::setLocalStartupCommand,
                                    onUpdateLocalConfig = { cmd, enabled, dir, autoCleanup, maxFiles ->
                                        appViewModel.updateLocalConfig(cmd, enabled, dir, autoCleanup, maxFiles)
                                    },
                                )
                            }

                            entry<AppRoute.AddServer> {
                                AddServerScreen(
                                    onSave = { config ->
                                        appViewModel.saveServer(config)
                                        onBack()
                                    },
                                    onBack = onBack,
                                )
                            }

                            entry<AppRoute.EditServer> { editServer ->
                                AddServerScreen(
                                    initialConfig = serverListState.servers.find { it.id == editServer.serverId },
                                    onSave = { config ->
                                        appViewModel.updateServer(config)
                                        onBack()
                                    },
                                    onBack = onBack,
                                )
                            }

                            entry<AppRoute.Settings> {
                                val settingsViewModel = viewModel {
                                    SettingsViewModel(container.preferences, container.fontStore)
                                }
                                SettingsRoute(
                                    viewModel = settingsViewModel,
                                    wallpaperController = wallpaperController,
                                    notificationState = appViewModel.notificationState,
                                    onBack = onBack,
                                )
                            }

                            entry<AppRoute.FileViewer> { viewer ->
                                SftpFileViewerScreen(
                                    file = File(viewer.path),
                                    onClose = onBack,
                                )
                            }

                            entry<AppRoute.Workspace>(
                                metadata = if (settings.animationsEnabled) SurfaceSafeTransitions else emptyMap(),
                            ) { route ->
                                WorkspaceRoute(
                                    serverId = route.serverId,
                                    appViewModel = appViewModel,
                                    container = container,
                                    settings = settings,
                                    appearance = TerminalAppearance(
                                        typeface = terminalTypeface,
                                        fontSizeRange = preferences.fontSizeRange,
                                        wallpaper = wallpaperConfig,
                                    ),
                                    host = TerminalHostCallbacks(
                                        onCommitContent = uploads::handleCommittedContent,
                                        onRequestMediaUpload = uploads::requestMediaUpload,
                                        onRequestFileUpload = uploads::requestFileUpload,
                                        onOpenUrl = onOpenUrl,
                                    ),
                                    onBack = onBack,
                                )
                            }
                        },
                    )

                    if (settings.debugHudEnabled && navigator.backStack.lastOrNull() is AppRoute.Workspace) {
                        DebugHud(modifier = Modifier.align(Alignment.TopEnd))
                    }

                    InAppNotificationBanner(
                        activeNotification = notification,
                        onDismiss = { appViewModel.notificationState.dismiss() },
                        onClick = {
                            notification?.let {
                                appViewModel.focusTerminalNotification(it.serverId, it.body, it.title)
                            }
                            appViewModel.notificationState.dismiss()
                        },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )

                    if (uploadInProgress) {
                        FileUploadBlockingOverlay(
                            onCancel = uploads::cancel,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }
}

// The terminal draws into a GLSurfaceView, which ignores alpha: the default cross-fade
// leaves it opaque on top until the transition ends. Slides move the surface with the page.
private val SurfaceSafeTransitions =
    NavDisplay.transitionSpec {
        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
    } + NavDisplay.popTransitionSpec {
        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
    } + NavDisplay.predictivePopTransitionSpec {
        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
    }

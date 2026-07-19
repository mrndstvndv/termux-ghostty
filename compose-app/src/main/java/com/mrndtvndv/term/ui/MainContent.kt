package com.mrndtvndv.term.ui

import android.content.SharedPreferences
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.MainViewModel
import com.mrndtvndv.term.ScreenState
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.ui.addserver.AddServerScreen
import com.mrndtvndv.term.ui.keyboard.PresetArrowsOnly
import com.mrndtvndv.term.ui.keyboard.PresetDoubleRow
import com.mrndtvndv.term.ui.keyboard.PresetSingleRow
import com.mrndtvndv.term.ui.notification.InAppNotificationBanner
import com.mrndtvndv.term.ui.serverlist.ServerListScreen
import com.mrndtvndv.term.ui.settings.SettingsScreen
import com.mrndtvndv.term.ui.sftp.SftpFileViewerScreen
import com.mrndtvndv.term.ui.theme.TerminalThemeSync
import com.mrndtvndv.term.ui.theme.TermuxGhosttyTheme
import com.mrndtvndv.term.ui.workspace.TerminalWorkspaceScreen
import com.termux.view.TerminalView
import java.io.File

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    viewModel: MainViewModel,
    sharedPreferences: SharedPreferences,
    customFontFamily: androidx.compose.ui.text.font.FontFamily?,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: (String) -> Unit,
    viewingFile: File?,
    onCloseFile: () -> Unit,
    getFileName: (Uri) -> String?,
    copyFontFile: (Uri) -> Unit,
    deleteFontFile: () -> Unit,
    fontFileExists: () -> Boolean,
) {
    val uiState by viewModel.uiState
    val savedServers by viewModel.savedServers
    val customFontName by viewModel.userPrefs.customFontName.collectAsState()
    val useCustomFontForWholeUi by viewModel.userPrefs.useCustomFontForWholeUi.collectAsState()
    val notification by viewModel.notificationState.notification.collectAsState()
    val navigator = rememberAppNavigator(
        activeTab = uiState.activeTab,
        onSetTab = { tab -> viewModel.setTab(tab) },
        onNavigateBack = { viewModel.navigateBack() },
    )

    val savedTheme = sharedPreferences.getString("app_theme", "Dark") ?: "Dark"
    val savedExtraKeysEnabled = sharedPreferences.getBoolean("extra_keys_enabled", true)
    val savedExtraKeysPreset =
        sharedPreferences.getString("extra_keys_preset", "Double Row") ?: "Double Row"
    val savedExtraKeysCustomJson =
        sharedPreferences.getString("extra_keys_custom_json", "[]") ?: "[]"
    val savedUseInAppBrowser = sharedPreferences.getBoolean("use_in_app_browser", false)
    val savedFontSize = sharedPreferences.getInt("font_size", 12)

    var appTheme by remember { mutableStateOf(savedTheme) }

    // Sync backstack with ViewModel screen state
    LaunchedEffect(uiState.screen) {
        when (uiState.screen) {
            is ScreenState.ServerList -> {
                if (navigator.backStack.lastOrNull() !is AppNavKey.ServerList) {
                    navigator.goBackToRoot()
                }
            }
            is ScreenState.TerminalWorkspace -> {
                val serverId = (uiState.screen as ScreenState.TerminalWorkspace).serverId
                if (navigator.backStack.lastOrNull() !is AppNavKey.TerminalWorkspace) {
                    navigator.navigate(AppNavKey.TerminalWorkspace(serverId))
                }
            }
        }
    }

    TermuxGhosttyTheme(theme = appTheme, customFontFamily = customFontFamily) {
        Surface(modifier = Modifier.fillMaxSize()) {
            var extraKeysEnabled by remember { mutableStateOf(savedExtraKeysEnabled) }
            var extraKeysPreset by remember { mutableStateOf(savedExtraKeysPreset) }
            var extraKeysCustomJson by remember { mutableStateOf(savedExtraKeysCustomJson) }
            var fontSize by remember { mutableStateOf(savedFontSize) }
            var useInAppBrowser by remember { mutableStateOf(savedUseInAppBrowser) }

            val pickFontLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let { copyFontFile(it) }
            }

            val onClearFontInternal: () -> Unit = {
                deleteFontFile()
            }

            // Resolve the active terminal session for theme sync
            val activeServer = (uiState.screen as? ScreenState.TerminalWorkspace)?.serverId
                ?.let { serverId -> viewModel.getServer(serverId) }
            val currentSession = activeServer?.terminalSession
            TerminalThemeSync(termSession = currentSession, appTheme = appTheme)

            val resolvedJson = remember(extraKeysPreset, extraKeysCustomJson) {
                when (extraKeysPreset) {
                    "Double Row" -> PresetDoubleRow
                    "Single Row" -> PresetSingleRow
                    "Arrows Only" -> PresetArrowsOnly
                    else -> extraKeysCustomJson
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // Navigation host
                androidx.navigation3.ui.NavDisplay(
                    backStack = navigator.backStack,
                    onBack = { navigator.goBack() },
                    entryProvider = androidx.navigation3.runtime.entryProvider {
                        entry<AppNavKey.ServerList> {
                            ServerListScreen(
                                servers = savedServers,
                                activeIds = viewModel.let { vm ->
                                    savedServers.map { it.id }
                                        .filter { id -> vm.getServer(id) != null }
                                        .toSet()
                                },
                                onTap = { serverId -> viewModel.connect(serverId) },
                                onDelete = { serverId -> viewModel.deleteServer(serverId) },
                                onAdd = { navigator.navigate(AppNavKey.AddServer) },
                                onSettingsClick = { navigator.navigate(AppNavKey.Settings) },
                            )
                        }

                        entry<AppNavKey.AddServer> {
                            AddServerScreen(
                                onSave = { config: ServerConfig ->
                                    viewModel.saveServer(config)
                                    navigator.goBack()
                                },
                                onBack = { navigator.goBack() },
                            )
                        }

                        entry<AppNavKey.Settings> {
                            SettingsScreen(
                                extraKeysEnabled = extraKeysEnabled,
                                onExtraKeysEnabledChange = { enabled ->
                                    extraKeysEnabled = enabled
                                    sharedPreferences.edit()
                                        .putBoolean("extra_keys_enabled", enabled).apply()
                                },
                                extraKeysPreset = extraKeysPreset,
                                onExtraKeysPresetChange = { preset ->
                                    extraKeysPreset = preset
                                    sharedPreferences.edit()
                                        .putString("extra_keys_preset", preset).apply()
                                },
                                extraKeysCustomJson = extraKeysCustomJson,
                                onExtraKeysCustomJsonChange = { json ->
                                    extraKeysCustomJson = json
                                    sharedPreferences.edit()
                                        .putString("extra_keys_custom_json", json).apply()
                                },
                                fontSize = fontSize,
                                onFontSizeChange = { newSize ->
                                    fontSize = newSize
                                    sharedPreferences.edit()
                                        .putInt("font_size", newSize).apply()
                                },
                                appTheme = appTheme,
                                onThemeChange = { newTheme ->
                                    appTheme = newTheme
                                    sharedPreferences.edit()
                                        .putString("app_theme", newTheme).apply()
                                },

                                customFontName = customFontName,
                                onSelectFont = { pickFontLauncher.launch("*/*") },
                                onClearFont = onClearFontInternal,
                                useCustomFontForWholeUi = useCustomFontForWholeUi,
                                onUseCustomFontForWholeUiChange = { enabled ->
                                    viewModel.userPrefs.setUseCustomFontForWholeUi(enabled, sharedPreferences)
                                },
                                useInAppBrowser = useInAppBrowser,
                                onUseInAppBrowserChange = { enabled ->
                                    useInAppBrowser = enabled
                                    sharedPreferences.edit()
                                        .putBoolean("use_in_app_browser", enabled).apply()
                                },
                                onBack = { navigator.goBack() },
                            )
                        }

                        entry<AppNavKey.TerminalWorkspace> {
                            val screen =
                                uiState.screen as? ScreenState.TerminalWorkspace
                            if (screen != null) {
                                val serverId = screen.serverId
                                val server = viewModel.getServer(serverId)
                                val sftpVM = viewModel.getSftpViewModel(serverId)
                                val reviewVM = viewModel.getReviewViewModel(serverId)

                                if (server != null) {
                                    TerminalWorkspaceScreen(
                                        session = server.terminalSession,
                                        sftpViewModel = sftpVM,
                                        reviewViewModel = reviewVM,
                                        extraKeysEnabled = extraKeysEnabled,
                                        extraKeysJson = resolvedJson,
                                        onViewCreated = onViewCreated,
                                        onViewReleased = onViewReleased,
                                        activeTab = uiState.activeTab,
                                        onOpenFile = onOpenFile,
                                        onOpenFileError = onOpenFileError,
                                        onTabSelected = { tab -> viewModel.setTab(tab) },
                                        browserUrl = uiState.browserUrl,
                                        onBrowserUrlChanged = { url ->
                                            viewModel.onBrowserUrlChanged(serverId, url)
                                        },
                                        onOpenUrl = onOpenUrl,
                                        onRefreshWorkspace = {
                                            onRefreshWorkspace(serverId)
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                } else {
                                    // Connection not ready yet
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        androidx.compose.material3.CircularProgressIndicator(
                                            modifier = Modifier.align(Alignment.Center)
                                        )
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        modifier = Modifier.align(Alignment.Center)
                                    )
                                }
                                SideEffect {
                                    if (uiState.screen is ScreenState.ServerList) {
                                        navigator.goBack()
                                    }
                                }
                            }
                        }
                    },
                )

                viewingFile?.let { file ->
                    SftpFileViewerScreen(
                        file = file,
                        onClose = onCloseFile,
                    )
                }

                InAppNotificationBanner(
                    activeNotification = notification,
                    onDismiss = { viewModel.notificationState.dismiss() },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

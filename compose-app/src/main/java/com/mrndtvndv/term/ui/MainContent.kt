package com.mrndtvndv.term.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.mrndtvndv.term.MainViewModel
import com.mrndtvndv.term.ScreenState
import com.mrndtvndv.term.ui.dashboard.DashboardScreen
import com.mrndtvndv.term.ui.notification.InAppNotificationBanner
import com.mrndtvndv.term.ui.theme.TermuxGhosttyTheme
import com.mrndtvndv.term.ui.workspace.TerminalWorkspaceScreen
import com.termux.view.TerminalView
import java.io.File
import android.content.SharedPreferences
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.mrndtvndv.term.ui.settings.SettingsScreen
import com.mrndtvndv.term.ui.theme.TerminalThemeSync

@Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainContent(
    viewModel: MainViewModel,
    sharedPreferences: SharedPreferences,
    customFontFamily: androidx.compose.ui.text.font.FontFamily?,
    onConnect: (String, Int, String, String) -> Unit,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    onOpenFile: (File) -> Unit,
    onOpenFileError: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onRefreshWorkspace: () -> Unit,
    viewingFile: File?,
    onCloseFile: () -> Unit,
    getFileName: (Uri) -> String?,
    copyFontFile: (Uri) -> Unit,
    deleteFontFile: () -> Unit,
    fontFileExists: () -> Boolean
) {
    val uiState by viewModel.uiState
    val navigator = rememberAppNavigator(viewModel)
    
    val savedTheme = sharedPreferences.getString("app_theme", "Dark") ?: "Dark"
    val savedExtraKeysEnabled = sharedPreferences.getBoolean("extra_keys_enabled", true)
    val savedExtraKeysPreset = sharedPreferences.getString("extra_keys_preset", "Double Row") ?: "Double Row"
    val savedExtraKeysCustomJson = sharedPreferences.getString("extra_keys_custom_json", "[]") ?: "[]"
    val savedHerdrIntegration = sharedPreferences.getBoolean("herdr_integration", false)
    val savedUseInAppBrowser = sharedPreferences.getBoolean("use_in_app_browser", false)
    val savedFontSize = sharedPreferences.getInt("font_size", 12)

    var appTheme by remember { mutableStateOf(savedTheme) }

    // Sync backstack with ViewModel screen state
    LaunchedEffect(uiState.screen) {
        when (uiState.screen) {
            is ScreenState.Dashboard -> {
                // If we are on TerminalWorkspace, we should go back to Dashboard
                if (navigator.backStack.lastOrNull() is AppNavKey.TerminalWorkspace) {
                    // Find Dashboard in backstack or reset
                    val hasDashboard = navigator.backStack.any { it is AppNavKey.Dashboard }
                    if (hasDashboard) {
                        while (navigator.backStack.lastOrNull() !is AppNavKey.Dashboard) {
                            navigator.backStack.removeAt(navigator.backStack.size - 1)
                        }
                    } else {
                        navigator.backStack.clear()
                        navigator.backStack.add(AppNavKey.Dashboard)
                    }
                }
            }
            is ScreenState.TerminalWorkspace -> {
                if (navigator.backStack.lastOrNull() !is AppNavKey.TerminalWorkspace) {
                    navigator.navigate(AppNavKey.TerminalWorkspace)
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
            var herdrIntegration by remember { mutableStateOf(savedHerdrIntegration) }
            var useInAppBrowser by remember { mutableStateOf(savedUseInAppBrowser) }

            val pickFontLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    copyFontFile(it)
                }
            }

            val onClearFontInternal: () -> Unit = {
                deleteFontFile()
            }

            val currentSession = (uiState.screen as? ScreenState.TerminalWorkspace)?.session
            TerminalThemeSync(termSession = currentSession, appTheme = appTheme)

            val resolvedJson = remember(extraKeysPreset, extraKeysCustomJson) {
                when (extraKeysPreset) {
                    "Double Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_DOUBLE_ROW
                    "Single Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_SINGLE_ROW
                    "Arrows Only" -> com.mrndtvndv.term.ui.dashboard.PRESET_ARROWS_ONLY
                    else -> extraKeysCustomJson
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                NavDisplay(
                    backStack = navigator.backStack,
                    onBack = { navigator.goBack() },
                    entryProvider = entryProvider {
                        entry<AppNavKey.Dashboard> {
                            DashboardScreen(
                                isLoading = uiState.isLoading,
                                errorMessage = uiState.error,
                                onConnect = onConnect,
                                onSettingsClick = { navigator.navigate(AppNavKey.Settings) },
                                initialHost = sharedPreferences.getString("ssh_host", "10.0.2.2") ?: "10.0.2.2",
                                initialPort = sharedPreferences.getInt("ssh_port", 2222),
                                initialUsername = sharedPreferences.getString("ssh_username", "root") ?: "root",
                                initialPassword = sharedPreferences.getString("ssh_password", "") ?: ""
                            )
                        }
                        entry<AppNavKey.Settings> {
                            SettingsScreen(
                                extraKeysEnabled = extraKeysEnabled,
                                onExtraKeysEnabledChange = { enabled ->
                                    extraKeysEnabled = enabled
                                    sharedPreferences.edit().putBoolean("extra_keys_enabled", enabled).apply()
                                },
                                extraKeysPreset = extraKeysPreset,
                                onExtraKeysPresetChange = { preset ->
                                    extraKeysPreset = preset
                                    sharedPreferences.edit().putString("extra_keys_preset", preset).apply()
                                },
                                extraKeysCustomJson = extraKeysCustomJson,
                                onExtraKeysCustomJsonChange = { json ->
                                    extraKeysCustomJson = json
                                    sharedPreferences.edit().putString("extra_keys_custom_json", json).apply()
                                },
                                fontSize = fontSize,
                                onFontSizeChange = { newSize ->
                                    fontSize = newSize
                                    sharedPreferences.edit().putInt("font_size", newSize).apply()
                                },
                                appTheme = appTheme,
                                onThemeChange = { newTheme ->
                                    appTheme = newTheme
                                    sharedPreferences.edit().putString("app_theme", newTheme).apply()
                                },
                                herdrIntegration = herdrIntegration,
                                onHerdrIntegrationChange = { enabled ->
                                    herdrIntegration = enabled
                                    sharedPreferences.edit().putBoolean("herdr_integration", enabled).apply()
                                },
                                customFontName = uiState.customFontName,
                                onSelectFont = { pickFontLauncher.launch("*/*") },
                                onClearFont = onClearFontInternal,
                                useCustomFontForWholeUi = uiState.useCustomFontForWholeUi,
                                onUseCustomFontForWholeUiChange = { enabled ->
                                    viewModel.setUseCustomFontForWholeUi(enabled)
                                    sharedPreferences.edit().putBoolean("use_custom_font_for_whole_ui", enabled).apply()
                                },
                                useInAppBrowser = useInAppBrowser,
                                onUseInAppBrowserChange = { enabled ->
                                    useInAppBrowser = enabled
                                    sharedPreferences.edit().putBoolean("use_in_app_browser", enabled).apply()
                                },
                                onBack = { navigator.goBack() }
                            )
                        }
                        entry<AppNavKey.TerminalWorkspace> {
                            val screen = uiState.screen as? ScreenState.TerminalWorkspace
                            if (screen != null) {
                                TerminalWorkspaceScreen(
                                    session = screen.session,
                                    sftpViewModel = screen.sftp,
                                    reviewViewModel = screen.review,
                                    extraKeysEnabled = extraKeysEnabled,
                                    extraKeysJson = resolvedJson,
                                    onViewCreated = onViewCreated,
                                    onViewReleased = onViewReleased,
                                    activeTab = uiState.activeTab,
                                    onOpenFile = onOpenFile,
                                    onOpenFileError = onOpenFileError,
                                    onTabSelected = { tab -> viewModel.setTab(tab) },
                                    browserUrl = uiState.browserUrl,
                                    onBrowserUrlChanged = { url -> viewModel.setBrowserUrl(url) },
                                    onOpenUrl = onOpenUrl,
                                    onRefreshWorkspace = onRefreshWorkspace,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                // Fallback if state is out of sync
                                Box(modifier = Modifier.fillMaxSize()) {
                                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                                }
                                // Trigger back if we accidentally landed here without a session
                                SideEffect {
                                    if (uiState.screen is ScreenState.Dashboard) {
                                        navigator.goBack()
                                    }
                                }
                            }
                        }
                    }
                )

                viewingFile?.let { file ->
                    com.mrndtvndv.term.ui.sftp.SftpFileViewerScreen(
                        file = file,
                        onClose = onCloseFile
                    )
                }

                InAppNotificationBanner(
                    activeNotification = uiState.notification,
                    onDismiss = { viewModel.dismissNotification() },
                    modifier = Modifier.align(Alignment.TopCenter)
                )
            }
        }
    }
}

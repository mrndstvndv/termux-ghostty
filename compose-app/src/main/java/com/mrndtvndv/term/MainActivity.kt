package com.mrndtvndv.term

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import com.mrndtvndv.term.data.ssh.native.NativeSshSession
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.ui.dashboard.DashboardScreen
import com.mrndtvndv.term.ui.theme.TermuxGhosttyTheme
import com.mrndtvndv.term.ui.workspace.TerminalWorkspaceScreen
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSessionIO
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.termux.shared.interact.ShareUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import org.json.JSONObject
import java.security.Security
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.mrndtvndv.term.service.SshSessionService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import java.io.File
import androidx.core.content.FileProvider
import androidx.activity.compose.BackHandler
import android.webkit.MimeTypeMap
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import com.mrndtvndv.term.ui.notification.InAppNotificationBanner

data class ActiveNotification(
    val title: String,
    val body: String,
    val timestamp: Long = System.currentTimeMillis()
)

sealed interface ScreenState {
    object Dashboard : ScreenState
    object TerminalWorkspace : ScreenState
}

class MainActivity : ComponentActivity() {

    private val sharedPreferences by lazy {
        getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
    }

    private var sshSession: SshSession? = null
    private var shellChannel: SshShellChannel? = null
    private var activeTerminalView: TerminalView? = null
    private var sshService: SshSessionService? = null
    private var isBound = false
    private val sshWriteChannel = kotlinx.coroutines.channels.Channel<ByteArray>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private var sshWriteJob: Job? = null
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as SshSessionService.LocalBinder
            sshService = binder.getService()
            isBound = true
            terminalSessionState.value?.let { session ->
                sshService?.addSession(session)
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            sshService = null
        }
    }

    private val terminalSessionState = mutableStateOf<TerminalSession?>(null)
    private val screenState = mutableStateOf<ScreenState>(ScreenState.Dashboard)
    
    private var sftpClient: SftpClient? = null
    private val sftpViewModelState = mutableStateOf<SftpViewModel?>(null)
    private val reviewViewModelState = mutableStateOf<ReviewViewModel?>(null)
    private val workspaceDirState = kotlinx.coroutines.flow.MutableStateFlow("/")
    private var activeWorkspaceKey: String? = null
    private var sshHost: String? = null
    private var sshPort: Int = 22
    private var sshUsername: String? = null
    private val sftpActivePageState = mutableIntStateOf(0)
    private val sftpVisibleState = mutableStateOf(false)
    private val browserUrlState = mutableStateOf("https://duckduckgo.com")
    
    private val connectionLoading = mutableStateOf(false)
    private val connectionError = mutableStateOf<String?>(null)

    private val activeNotificationState = mutableStateOf<ActiveNotification?>(null)
    private var nextNotificationId = com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATION_ID_BASE

    @Synchronized
    private fun getNextTerminalProtocolNotificationId(): Int {
        val id = nextNotificationId
        nextNotificationId++
        return id
    }

    private fun handleNotification(title: String?, body: String?) {
        val isForeground = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)
        if (isForeground) {
            activeNotificationState.value = ActiveNotification(
                title = title ?: "Terminal Notification",
                body = body ?: ""
            )
        } else {
            showSystemNotification(title, body)
        }
    }

    private fun showSystemNotification(title: String?, body: String?) {
        val notificationManager = com.termux.shared.notification.NotificationUtils.getNotificationManager(this) ?: return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            com.termux.shared.notification.NotificationUtils.setupNotificationChannel(
                this,
                com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
                com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
        }
        
        val normalizedTitle = title ?: "Terminal Notification"
        val normalizedBody = body ?: ""
        
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val builder = com.termux.shared.notification.NotificationUtils.geNotificationBuilder(
            this,
            com.termux.shared.termux.TermuxConstants.TERMUX_TERMINAL_PROTOCOL_NOTIFICATIONS_NOTIFICATION_CHANNEL_ID,
            Notification.PRIORITY_DEFAULT,
            normalizedTitle,
            normalizedBody,
            normalizedBody,
            contentIntent,
            null,
            com.termux.shared.notification.NotificationUtils.NOTIFICATION_MODE_ALL
        ) ?: return
        
        builder.setSmallIcon(android.R.drawable.ic_dialog_info)
        builder.setAutoCancel(true)
        
        notificationManager.notify(
            getNextTerminalProtocolNotificationId(),
            builder.build()
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Remove ancient system BC provider and insert our modern provider
        Security.removeProvider("BC")
        Security.insertProviderAt(BouncyCastleProvider(), 1)
        
        // Perform Conscrypt security provider initialization
        Security.insertProviderAt(Conscrypt.newProvider(), 2)

        val savedHost = sharedPreferences.getString("ssh_host", "10.0.2.2") ?: "10.0.2.2"
        val savedPort = sharedPreferences.getInt("ssh_port", 2222)
        val savedUsername = sharedPreferences.getString("ssh_username", "root") ?: "root"
        val savedPassword = sharedPreferences.getString("ssh_password", "") ?: ""

        val savedExtraKeysEnabled = sharedPreferences.getBoolean("extra_keys_enabled", true)
        val savedExtraKeysPreset = sharedPreferences.getString("extra_keys_preset", "Double Row") ?: "Double Row"
        val savedExtraKeysCustomJson = sharedPreferences.getString("extra_keys_custom_json", "[]") ?: "[]"
        val savedTheme = sharedPreferences.getString("app_theme", "Dark") ?: "Dark"
        val savedHerdrIntegration = sharedPreferences.getBoolean("herdr_integration", false)
        val savedUseInAppBrowser = sharedPreferences.getBoolean("use_in_app_browser", false)

        val sizes = com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.getDefaultFontSizes(this)
        val defaultFontSize = sizes[0]
        val minFontSize = sizes[1]
        val maxFontSize = sizes[2]
        val savedFontSize = sharedPreferences.getInt("font_size", defaultFontSize).coerceIn(minFontSize, maxFontSize)

        setContent {
            var appTheme by remember { mutableStateOf(savedTheme) }

            TermuxGhosttyTheme(theme = appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val currentScreen by screenState
                    val termSession by terminalSessionState
                    val isLoading by connectionLoading
                    val errorMessage by connectionError
                    val activeNotification by activeNotificationState
 
                    var extraKeysEnabled by remember { mutableStateOf(savedExtraKeysEnabled) }
                    var extraKeysPreset by remember { mutableStateOf(savedExtraKeysPreset) }
                    var extraKeysCustomJson by remember { mutableStateOf(savedExtraKeysCustomJson) }
                    var fontSize by remember { mutableStateOf(savedFontSize) }
                    var customFontName by remember {
                        mutableStateOf(sharedPreferences.getString("custom_font_name", null))
                    }
                    var herdrIntegration by remember { mutableStateOf(savedHerdrIntegration) }
                    var useInAppBrowser by remember { mutableStateOf(savedUseInAppBrowser) }

                    val pickFontLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.GetContent()
                    ) { uri: Uri? ->
                        uri?.let {
                            try {
                                val inputStream = contentResolver.openInputStream(uri)
                                if (inputStream != null) {
                                    val fontFile = File(filesDir, "font.ttf")
                                    fontFile.outputStream().use { outputStream ->
                                        inputStream.copyTo(outputStream)
                                    }
                                    val name = getFileName(this@MainActivity, uri) ?: "custom_font.ttf"
                                    sharedPreferences.edit()
                                        .putString("custom_font_name", name)
                                        .apply()
                                    customFontName = name
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Failed to copy custom font", e)
                            }
                        }
                    }

                    val onClearFont: () -> Unit = {
                        val fontFile = File(filesDir, "font.ttf")
                        if (fontFile.exists()) {
                            fontFile.delete()
                        }
                        sharedPreferences.edit()
                            .remove("custom_font_name")
                            .apply()
                        customFontName = null
                    }

                    val currentThemeScheme = MaterialTheme.colorScheme
                    val isThemeDark = !appTheme.equals("Light", ignoreCase = true)

                    LaunchedEffect(currentThemeScheme, termSession) {
                        val properties = java.util.Properties()
                        
                        val primary = currentThemeScheme.primary.toArgb()
                        val primaryContainer = currentThemeScheme.primaryContainer.toArgb()
                        val secondary = currentThemeScheme.secondary.toArgb()
                        val secondaryContainer = currentThemeScheme.secondaryContainer.toArgb()
                        val tertiary = currentThemeScheme.tertiary.toArgb()
                        val tertiaryContainer = currentThemeScheme.tertiaryContainer.toArgb()
                        val surface = currentThemeScheme.surface.toArgb()
                        val onSurface = currentThemeScheme.onSurface.toArgb()
                        val onSurfaceVariant = currentThemeScheme.onSurfaceVariant.toArgb()
                        val outline = currentThemeScheme.outline.toArgb()
                        val error = currentThemeScheme.error.toArgb()
                        val errorContainer = currentThemeScheme.errorContainer.toArgb()
                        val surfaceContainerHighest = currentThemeScheme.surfaceVariant.toArgb()

                        fun shiftTone(colorVal: Int, toneVal: Double): Int {
                            val hct = com.google.android.material.color.utilities.Hct.fromInt(colorVal)
                            hct.setTone(toneVal)
                            return hct.toInt()
                        }

                        fun toTerminalColor(color: Int): String {
                            return String.format(java.util.Locale.US, "#%02x%02x%02x", android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color))
                        }

                        properties.setProperty("foreground", toTerminalColor(onSurface))
                        properties.setProperty("background", toTerminalColor(surface))
                        properties.setProperty("cursor", toTerminalColor(primary))
                        properties.setProperty("color0", toTerminalColor(surfaceContainerHighest))
                        properties.setProperty("color1", toTerminalColor(error))
                        properties.setProperty("color2", toTerminalColor(tertiary))
                        properties.setProperty("color3", toTerminalColor(primaryContainer))
                        properties.setProperty("color4", toTerminalColor(primary))
                        properties.setProperty("color5", toTerminalColor(secondary))
                        properties.setProperty("color6", toTerminalColor(tertiaryContainer))
                        properties.setProperty("color7", toTerminalColor(onSurfaceVariant))
                        properties.setProperty("color8", toTerminalColor(outline))
                        properties.setProperty("color9", toTerminalColor(errorContainer))
                        properties.setProperty("color10", toTerminalColor(shiftTone(tertiary, if (isThemeDark) 88.0 else 28.0)))
                        properties.setProperty("color11", toTerminalColor(shiftTone(primary, if (isThemeDark) 88.0 else 28.0)))
                        properties.setProperty("color12", toTerminalColor(primaryContainer))
                        properties.setProperty("color13", toTerminalColor(secondaryContainer))
                        properties.setProperty("color14", toTerminalColor(shiftTone(tertiaryContainer, if (isThemeDark) 92.0 else 24.0)))
                        properties.setProperty("color15", toTerminalColor(onSurface))

                        TerminalColors.COLOR_SCHEME.updateWith(properties)
                        termSession?.reloadColorScheme()
                    }

                    LaunchedEffect(currentScreen) {
                        if (currentScreen is ScreenState.Dashboard) {
                            fontSize = sharedPreferences.getInt("font_size", defaultFontSize).coerceIn(minFontSize, maxFontSize)
                        }
                    }

                    val onFontSizeChange: (Int) -> Unit = { newSize ->
                        val clampedSize = newSize.coerceIn(minFontSize, maxFontSize)
                        fontSize = clampedSize
                        sharedPreferences.edit().putInt("font_size", clampedSize).apply()
                    }

                    val onExtraKeysEnabledChange: (Boolean) -> Unit = { enabled ->
                        extraKeysEnabled = enabled
                        sharedPreferences.edit().putBoolean("extra_keys_enabled", enabled).apply()
                    }
                    val onExtraKeysPresetChange: (String) -> Unit = { preset ->
                        extraKeysPreset = preset
                        sharedPreferences.edit().putString("extra_keys_preset", preset).apply()
                    }
                    val onExtraKeysCustomJsonChange: (String) -> Unit = { json ->
                        extraKeysCustomJson = json
                        sharedPreferences.edit().putString("extra_keys_custom_json", json).apply()
                    }


                    val resolvedJson = remember(extraKeysPreset, extraKeysCustomJson) {
                        when (extraKeysPreset) {
                            "Double Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_DOUBLE_ROW
                            "Single Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_SINGLE_ROW
                            "Arrows Only" -> com.mrndtvndv.term.ui.dashboard.PRESET_ARROWS_ONLY
                            else -> extraKeysCustomJson
                        }
                    }

                    Box(modifier = Modifier.fillMaxSize()) {
                        when (currentScreen) {
                            is ScreenState.Dashboard -> {
                                DashboardScreen(
                                    isLoading = isLoading,
                                    errorMessage = errorMessage,
                                    initialHost = savedHost,
                                    initialPort = savedPort,
                                    initialUsername = savedUsername,
                                    initialPassword = savedPassword,
                                    onConnect = { host, port, username, password ->
                                        connectSsh(host, port, username, password)
                                    },
                                    extraKeysEnabled = extraKeysEnabled,
                                    onExtraKeysEnabledChange = onExtraKeysEnabledChange,
                                    extraKeysPreset = extraKeysPreset,
                                    onExtraKeysPresetChange = onExtraKeysPresetChange,
                                    extraKeysCustomJson = extraKeysCustomJson,
                                    onExtraKeysCustomJsonChange = onExtraKeysCustomJsonChange,
                                    fontSize = fontSize,
                                    onFontSizeChange = onFontSizeChange,
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
                                    customFontName = customFontName,
                                    onSelectFont = {
                                        pickFontLauncher.launch("*/*")
                                    },
                                    onClearFont = onClearFont,
                                    useInAppBrowser = useInAppBrowser,
                                    onUseInAppBrowserChange = { enabled ->
                                        useInAppBrowser = enabled
                                        sharedPreferences.edit().putBoolean("use_in_app_browser", enabled).apply()
                                    }
                                )
                            }
                            is ScreenState.TerminalWorkspace -> {
                                BackHandler(enabled = sftpActivePageState.intValue != 0) {
                                    sftpActivePageState.intValue = 0
                                }
                                if (termSession != null) {
                                     TerminalWorkspaceScreen(
                                         session = termSession!!,
                                         sftpViewModel = sftpViewModelState.value,
                                         reviewViewModel = reviewViewModelState.value,
                                         extraKeysEnabled = extraKeysEnabled,
                                         extraKeysJson = resolvedJson,
                                         onViewCreated = { view ->
                                             activeTerminalView = view
                                             registerForContextMenu(view)
                                         },
                                         onViewReleased = { view ->
                                             activeTerminalView?.let { unregisterForContextMenu(it) }
                                             if (activeTerminalView === view) {
                                                 activeTerminalView = null
                                             }
                                         },
                                         activePage = sftpActivePageState.intValue,
                                         onOpenFile = { file ->
                                             openDownloadedFile(file)
                                         },
                                         onOpenFileError = { errorMsg ->
                                             handleNotification("SFTP Error", errorMsg)
                                         },
                                          onPageSelected = { page ->
                                               sftpActivePageState.intValue = page
                                          },
                                          browserUrl = browserUrlState.value,
                                          onBrowserUrlChanged = { url ->
                                              browserUrlState.value = url
                                              activeWorkspaceKey?.let { key ->
                                                  sharedPreferences.edit().putString("browser_last_url_$key", url).apply()
                                              }
                                          },
                                          onOpenUrl = { url ->
                                              if (useInAppBrowser) {
                                                  browserUrlState.value = url
                                              } else {
                                                  ShareUtils.openUrl(this@MainActivity, url)
                                              }
                                          },
                                          onRefreshWorkspace = {
                                              if (sharedPreferences.getBoolean("herdr_integration", false)) {
                                                  sshSession?.let { currentSession ->
                                                      lifecycleScope.launch(Dispatchers.IO) {
                                                          try {
                                                              val output = currentSession.execCommand("export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; herdr workspace list; herdr pane list")
                                                              var focusedWsId: String? = null
                                                              var wsLabel: String? = null
                                                              val panes = mutableListOf<JSONObject>()

                                                              output.split("\n").forEach { line ->
                                                                  val trimmed = line.trim()
                                                                  if (trimmed.isNotEmpty()) {
                                                                      try {
                                                                          val json = JSONObject(trimmed)
                                                                          val id = json.optString("id")
                                                                          val result = json.optJSONObject("result")
                                                                          if (result != null) {
                                                                              if (id == "cli:workspace:list") {
                                                                                  val wsArray = result.optJSONArray("workspaces")
                                                                                  if (wsArray != null) {
                                                                                      for (i in 0 until wsArray.length()) {
                                                                                          val ws = wsArray.optJSONObject(i)
                                                                                          if (ws != null && ws.optBoolean("focused", false)) {
                                                                                              focusedWsId = ws.optString("workspace_id").takeIf { it.isNotEmpty() }
                                                                                              wsLabel = ws.optString("label").takeIf { it.isNotEmpty() }
                                                                                              break
                                                                                          }
                                                                                      }
                                                                                  }
                                                                              } else if (id == "cli:pane:list") {
                                                                                  val paneArray = result.optJSONArray("panes")
                                                                                  if (paneArray != null) {
                                                                                      for (i in 0 until paneArray.length()) {
                                                                                          val pane = paneArray.optJSONObject(i)
                                                                                          if (pane != null) panes.add(pane)
                                                                                      }
                                                                                  }
                                                                              }
                                                                          }
                                                                      } catch (je: Exception) {}
                                                                  }
                                                              }

                                                              val workspaceName = wsLabel ?: focusedWsId
                                                              val newWorkspaceKey = if (!workspaceName.isNullOrEmpty()) {
                                                                  "${workspaceName}_${sshHost}_$sshUsername"
                                                              } else {
                                                                  "$sshUsername@$sshHost:$sshPort"
                                                              }

                                                              var paneCwd: String? = null
                                                              if (!focusedWsId.isNullOrEmpty()) {
                                                                  for (pane in panes) {
                                                                      if (pane.optString("workspace_id") == focusedWsId && pane.optBoolean("focused", false)) {
                                                                          paneCwd = pane.optString("cwd").takeIf { it.isNotEmpty() }
                                                                          break
                                                                      }
                                                                  }
                                                              }

                                                              withContext(Dispatchers.Main) {
                                                                  if (newWorkspaceKey != activeWorkspaceKey) {
                                                                      activeWorkspaceKey = newWorkspaceKey
                                                                      val savedDir = sharedPreferences.getString("sftp_last_dir_$newWorkspaceKey", null)
                                                                      val finalCwd = if (!savedDir.isNullOrEmpty()) savedDir else (paneCwd ?: "/")
                                                                      
                                                                      workspaceDirState.value = finalCwd
                                                                      sftpViewModelState.value?.navigateTo(finalCwd)
                                                                      
                                                                      val savedUrl = sharedPreferences.getString("browser_last_url_$newWorkspaceKey", "https://duckduckgo.com") ?: "https://duckduckgo.com"
                                                                      browserUrlState.value = savedUrl
                                                                  }
                                                              }
                                                          } catch (e: Exception) {
                                                              // ignore
                                                          }
                                                      }
                                                  }
                                              }
                                          },
                                         modifier = Modifier.fillMaxSize()
                                     )
                                }
                            }
                        }

                        InAppNotificationBanner(
                            activeNotification = activeNotification,
                            onDismiss = { activeNotificationState.value = null },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }

    private fun connectSsh(host: String, port: Int, username: String, passwordString: String) {
        sshHost = host
        sshPort = port
        sshUsername = username
        activeWorkspaceKey = null
        connectionLoading.value = true
        connectionError.value = null
        
        lifecycleScope.launch {
            try {
                val session = NativeSshSession()
                sshSession = session
                
                withContext(Dispatchers.IO) {
                    session.connect(SshConfig(host, port, username))
                    session.authenticate(SshAuth.Password(passwordString.toCharArray()))
                }
                
                val herdrIntegrationValue = sharedPreferences.getBoolean("herdr_integration", false)
                val termType = if (herdrIntegrationValue) "xterm-ghostty" else "xterm-256color"
                val channel = session.openShellChannel(termType, 80, 24, herdrIntegrationValue)
                shellChannel = channel
                
                sshWriteJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        for (dataCopy in sshWriteChannel) {
                            try {
                                channel.outputStream.write(dataCopy)
                                channel.outputStream.flush()
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val sessionClient = object : TermuxTerminalSessionClientBase() {
                    override fun onFrameAvailable(changedSession: TerminalSession) {
                        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
                        activeTerminalView?.onFrameAvailable()
                    }

                    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
                        ShareUtils.copyTextToClipboard(this@MainActivity, text)
                    }

                    override fun onPasteTextFromClipboard(session: TerminalSession?) {
                        val text = ShareUtils.getTextStringFromClipboardIfSet(this@MainActivity, true)
                        if (text != null) {
                            session?.paste(text)
                        }
                    }

                    override fun onTerminalProtocolNotification(
                        session: TerminalSession,
                        title: String?,
                        body: String?
                    ) {
                        handleNotification(title, body)
                    }
                }
                val sessionIo = object : TerminalSessionIO {
                    override fun write(data: ByteArray?, offset: Int, count: Int) {
                        if (data != null && count > 0) {
                            val dataCopy = data.copyOfRange(offset, offset + count)
                            sshWriteChannel.trySend(dataCopy)
                        }
                    }

                    override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                channel.resizeWindow(columns, rows, columns * cellWidth, rows * cellHeight)
                            } catch (e: Exception) {
                                android.util.Log.w("MainActivity", "resizeWindow failed", e)
                            }
                        }
                    }

                    override fun onClose() {
                        lifecycleScope.launch(Dispatchers.Main) {
                            cleanupConnection()
                            screenState.value = ScreenState.Dashboard
                        }
                    }
                }
                
                val termSession = TerminalSession(2000, sessionClient, sessionIo)
                termSession.setSshSessionHandle(session.nativeSessionHandle)
                terminalSessionState.value = termSession
                
                val serviceIntent = Intent(this@MainActivity, SshSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                
                sharedPreferences.edit().apply {
                    putString("ssh_host", host)
                    putInt("ssh_port", port)
                    putString("ssh_username", username)
                    putString("ssh_password", passwordString)
                    apply()
                }

                // Resolve workspace and configure browser/SFTP in background
                                lifecycleScope.launch(Dispatchers.IO) {
                                    val isHerdrEnabled = sharedPreferences.getBoolean("herdr_integration", false)
                                    var workspaceName: String? = null
                                    var resolvedCwd = "/"

                                    if (isHerdrEnabled) {
                                        try {
                                            val output = session.execCommand("export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; herdr workspace list; herdr pane list")
                                            var focusedWsId: String? = null
                                            var wsLabel: String? = null
                                            val panes = mutableListOf<JSONObject>()

                                            output.split("\n").forEach { line ->
                                                val trimmed = line.trim()
                                                if (trimmed.isNotEmpty()) {
                                                    try {
                                                        val json = JSONObject(trimmed)
                                                        val id = json.optString("id")
                                                        val result = json.optJSONObject("result")
                                                        if (result != null) {
                                                            if (id == "cli:workspace:list") {
                                                                val wsArray = result.optJSONArray("workspaces")
                                                                if (wsArray != null) {
                                                                    for (i in 0 until wsArray.length()) {
                                                                        val ws = wsArray.optJSONObject(i)
                                                                        if (ws != null && ws.optBoolean("focused", false)) {
                                                                            focusedWsId = ws.optString("workspace_id").takeIf { it.isNotEmpty() }
                                                                            wsLabel = ws.optString("label").takeIf { it.isNotEmpty() }
                                                                            break
                                                                        }
                                                                    }
                                                                }
                                                            } else if (id == "cli:pane:list") {
                                                                val paneArray = result.optJSONArray("panes")
                                                                if (paneArray != null) {
                                                                    for (i in 0 until paneArray.length()) {
                                                                        val pane = paneArray.optJSONObject(i)
                                                                        if (pane != null) {
                                                                            panes.add(pane)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    } catch (je: Exception) {
                                                        // ignore
                                                    }
                                                }
                                            }

                                            var paneCwd: String? = null
                                            if (!focusedWsId.isNullOrEmpty()) {
                                                for (pane in panes) {
                                                    if (pane.optString("workspace_id") == focusedWsId && pane.optBoolean("focused", false)) {
                                                        paneCwd = pane.optString("cwd").takeIf { it.isNotEmpty() }
                                                        break
                                                    }
                                                }
                                            }
                                            if (!paneCwd.isNullOrEmpty()) {
                                                resolvedCwd = paneCwd
                                            }
                                            if (!wsLabel.isNullOrEmpty()) {
                                                workspaceName = wsLabel
                                            } else if (!focusedWsId.isNullOrEmpty()) {
                                                workspaceName = focusedWsId
                                            }
                                        } catch (e: Exception) {
                                            // ignore
                                        }
                                    }

                                    val key = if (!workspaceName.isNullOrEmpty()) {
                                        "${workspaceName}_${host}_$username"
                                    } else {
                                        "$username@$host:$port"
                                    }

                                    activeWorkspaceKey = key

                                    val savedUrl = sharedPreferences.getString("browser_last_url_$key", "https://duckduckgo.com") ?: "https://duckduckgo.com"
                                    withContext(Dispatchers.Main) {
                                        browserUrlState.value = savedUrl
                                        if (isHerdrEnabled) {
                                            workspaceDirState.value = resolvedCwd
                                            reviewViewModelState.value = ReviewViewModel(
                                                execCommand = { cmd -> session.execCommand(cmd) },
                                                workspaceDir = workspaceDirState
                                            )
                                        }
                                    }

                                    try {
                                        val sftp = session.openSftpClient()
                                        sftpClient = sftp
                                        
                                        val savedDir = sharedPreferences.getString("sftp_last_dir_$key", null)
                                        val initialDir = if (!savedDir.isNullOrEmpty()) savedDir else resolvedCwd

                                        withContext(Dispatchers.Main) {
                                            if (isHerdrEnabled) {
                                                workspaceDirState.value = initialDir
                                            }
                                            val viewModel = SftpViewModel(sftp, SavedStateHandle(), initialDir)
                                            viewModel.onPathChanged = { path ->
                                                activeWorkspaceKey?.let { k ->
                                                    sharedPreferences.edit().putString("sftp_last_dir_$k", path).apply()
                                                }
                                                workspaceDirState.value = path
                                            }
                                            sftpViewModelState.value = viewModel
                                            sftpVisibleState.value = true
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.e("MainActivity", "Failed to initialize native SFTP", e)
                                    }
                                }

                connectionLoading.value = false
                screenState.value = ScreenState.TerminalWorkspace
                
            } catch (e: Exception) {
                cleanupConnection()
                connectionLoading.value = false
                connectionError.value = e.localizedMessage ?: "Failed to connect"
            }
        }
    }

    private fun cleanupConnection() {
        sshWriteJob?.cancel()
        sshWriteJob = null
        while (true) {
            val result = sshWriteChannel.tryReceive()
            if (result.isFailure || result.isClosed) break
        }
        val session = terminalSessionState.value
        terminalSessionState.value = null
 
        val handle = session?.mHandle
        if (handle != null) {
            sshService?.removeSession(handle)
        }
        if (isBound) {
            unbindService(connection)
            isBound = false
            sshService = null
        }
        try {
            shellChannel?.close()
        } catch (e: Exception) {}
        shellChannel = null
        try {
            sftpClient?.close()
        } catch (e: Exception) {}
        sftpClient = null
        sftpViewModelState.value = null
        reviewViewModelState.value = null
        sftpActivePageState.intValue = 0
        sftpVisibleState.value = false
        activeWorkspaceKey = null
        try {
            sshSession?.disconnect()
        } catch (e: Exception) {}
        sshSession = null
        session?.finishIfRunning()
    }

    override fun onCreateContextMenu(
        menu: android.view.ContextMenu,
        v: android.view.View,
        menuInfo: android.view.ContextMenu.ContextMenuInfo?
    ) {
        super.onCreateContextMenu(menu, v, menuInfo)
        val view = activeTerminalView ?: return
        if (v === view) {
            menu.add(android.view.Menu.NONE, 1, android.view.Menu.NONE, "Share selected text").apply {
                isEnabled = !view.storedSelectedText.isNullOrEmpty()
            }
            menu.add(android.view.Menu.NONE, 2, android.view.Menu.NONE, "Share transcript")
        }
    }

    override fun onContextItemSelected(item: android.view.MenuItem): Boolean {
        val view = activeTerminalView ?: return super.onContextItemSelected(item)
        val session = terminalSessionState.value ?: return super.onContextItemSelected(item)
        return when (item.itemId) {
            1 -> {
                val selectedText = view.storedSelectedText
                if (!selectedText.isNullOrEmpty()) {
                    ShareUtils.shareText(this, "Terminal selection", selectedText)
                    view.unsetStoredSelectedText()
                }
                true
            }
            2 -> {
                val transcript = session.getTerminalContent()?.getTranscriptText(true, true) ?: ""
                ShareUtils.shareText(this, "Terminal transcript", transcript)
                true
            }
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onDestroy() {
        cleanupConnection()
        super.onDestroy()
    }

    private fun openDownloadedFile(file: File) {
        try {
            val authority = "${packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            
            val extension = file.extension.lowercase()
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val chooser = Intent.createChooser(intent, "Open file with...")
            startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to open file", e)
            handleNotification("Error", "Failed to open file: ${e.localizedMessage}")
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = cursor.getString(index)
                    }
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }
}

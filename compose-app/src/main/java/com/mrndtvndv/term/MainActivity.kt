package com.mrndtvndv.term

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.lifecycleScope
import com.mrndtvndv.term.data.ssh.jvm.JvmSshSession
import com.mrndtvndv.term.data.ssh.native.NativeSshSession
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.ui.dashboard.DashboardScreen
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.theme.TermuxGhosttyTheme
import com.mrndtvndv.term.ui.workspace.TerminalWorkspaceScreen
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionIO
import com.termux.view.TerminalView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import java.security.Security
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.mrndtvndv.term.service.SshSessionService

sealed interface ScreenState {
    object Dashboard : ScreenState
    object TerminalWorkspace : ScreenState
}

class MainActivity : ComponentActivity() {

    private val sharedPreferences by lazy {
        getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
    }

    private var useNativePiping = true
    private var sshSession: SshSession? = null
    private var shellChannel: SshShellChannel? = null
    private var sftpClient: SftpClient? = null
    private var activeTerminalView: TerminalView? = null
    private var sshService: SshSessionService? = null
    private var isBound = false
    private val sshLock = Any()
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
    private val sftpViewModelState = mutableStateOf<SftpViewModel?>(null)
    private val screenState = mutableStateOf<ScreenState>(ScreenState.Dashboard)
    
    private val connectionLoading = mutableStateOf(false)
    private val connectionError = mutableStateOf<String?>(null)
    
    private var readerJob: Job? = null

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
        val savedUseNativePiping = sharedPreferences.getBoolean("use_native_piping", true)

        val sizes = com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences.getDefaultFontSizes(this)
        val defaultFontSize = sizes[0]
        val minFontSize = sizes[1]
        val maxFontSize = sizes[2]
        val savedFontSize = sharedPreferences.getInt("font_size", defaultFontSize).coerceIn(minFontSize, maxFontSize)

        setContent {
            TermuxGhosttyTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val currentScreen by screenState
                    val termSession by terminalSessionState
                    val sftpViewModel by sftpViewModelState
                    val isLoading by connectionLoading
                    val errorMessage by connectionError

                    var extraKeysEnabled by remember { mutableStateOf(savedExtraKeysEnabled) }
                    var extraKeysPreset by remember { mutableStateOf(savedExtraKeysPreset) }
                    var extraKeysCustomJson by remember { mutableStateOf(savedExtraKeysCustomJson) }
                    var fontSize by remember { mutableStateOf(savedFontSize) }
                    var useNativePipingState by remember { mutableStateOf(savedUseNativePiping) }
                    useNativePiping = useNativePipingState

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
                    val onUseNativePipingChange: (Boolean) -> Unit = { enabled ->
                        useNativePipingState = enabled
                        sharedPreferences.edit().putBoolean("use_native_piping", enabled).apply()
                    }

                    val resolvedJson = remember(extraKeysPreset, extraKeysCustomJson) {
                        when (extraKeysPreset) {
                            "Double Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_DOUBLE_ROW
                            "Single Row" -> com.mrndtvndv.term.ui.dashboard.PRESET_SINGLE_ROW
                            "Arrows Only" -> com.mrndtvndv.term.ui.dashboard.PRESET_ARROWS_ONLY
                            else -> extraKeysCustomJson
                        }
                    }

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
                                useNativePiping = useNativePipingState,
                                onUseNativePipingChange = onUseNativePipingChange
                            )
                        }
                        is ScreenState.TerminalWorkspace -> {
                            if (termSession != null && sftpViewModel != null) {
                                TerminalWorkspaceScreen(
                                    session = termSession!!,
                                    sftpViewModel = sftpViewModel!!,
                                    extraKeysEnabled = extraKeysEnabled,
                                    extraKeysJson = resolvedJson,
                                    onViewCreated = { view ->
                                        activeTerminalView = view
                                    },
                                    onViewReleased = {
                                        activeTerminalView = null
                                    },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun connectSsh(host: String, port: Int, username: String, passwordString: String) {
        connectionLoading.value = true
        connectionError.value = null
        
        lifecycleScope.launch {
            try {
                val session = if (useNativePiping) NativeSshSession() else JvmSshSession()
                sshSession = session
                
                withContext(Dispatchers.IO) {
                    session.connect(SshConfig(host, port, username))
                    session.authenticate(SshAuth.Password(passwordString.toCharArray()))
                }
                
                val channel = session.openShellChannel("xterm-256color", 80, 24)
                shellChannel = channel
                
                val sftp = session.openSftpClient()
                sftpClient = sftp
                
                sshWriteJob = lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        for (dataCopy in sshWriteChannel) {
                            synchronized(sshLock) {
                                try {
                                    channel.outputStream.write(dataCopy)
                                    channel.outputStream.flush()
                                } catch (e: Exception) {
                                    // ignore
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // ignore
                    }
                }

                val sessionClient = object : TermuxTerminalSessionClientBase() {
                    override fun onFrameAvailable(changedSession: TerminalSession) {
                        activeTerminalView?.onFrameAvailable()
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
                            synchronized(sshLock) {
                                try {
                                    channel.resizeWindow(columns, rows, columns * cellWidth, rows * cellHeight)
                                } catch (e: Exception) {
                                    android.util.Log.w("MainActivity", "resizeWindow failed", e)
                                }
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
                if (useNativePiping && session is NativeSshSession) {
                    termSession.setSshSessionHandle(session.nativeSessionHandle)
                }
                terminalSessionState.value = termSession
                
                val serviceIntent = Intent(this@MainActivity, SshSessionService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
                
                if (!useNativePiping) {
                    readerJob = lifecycleScope.launch(Dispatchers.IO) {
                        val buffer = ByteArray(16384)
                        try {
                            val input = channel.inputStream
                            while (isActive) {
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) break
                                if (bytesRead > 0) {
                                    termSession.appendOutput(buffer, 0, bytesRead)
                                }
                            }
                        } catch (e: Exception) {
                            if (e !is kotlinx.coroutines.CancellationException) {
                                android.util.Log.w("MainActivity", "SSH reader error", e)
                            }
                        } finally {
                            if (coroutineContext[Job]?.isCancelled != true) {
                                withContext(Dispatchers.Main) {
                                    if (screenState.value is ScreenState.TerminalWorkspace) {
                                        cleanupConnection()
                                        screenState.value = ScreenState.Dashboard
                                    }
                                }
                            }
                        }
                    }
                }
                
                val sftpVM = SftpViewModel(sftp, SavedStateHandle())
                sftpViewModelState.value = sftpVM
                
                sharedPreferences.edit().apply {
                    putString("ssh_host", host)
                    putInt("ssh_port", port)
                    putString("ssh_username", username)
                    putString("ssh_password", passwordString)
                    apply()
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
        readerJob?.cancel()
        readerJob = null
        sshWriteJob?.cancel()
        sshWriteJob = null
        while (true) {
            val result = sshWriteChannel.tryReceive()
            if (result.isFailure || result.isClosed) break
        }
        val handle = terminalSessionState.value?.mHandle
        if (handle != null) {
            sshService?.removeSession(handle)
        }
        if (isBound) {
            unbindService(connection)
            isBound = false
            sshService = null
        }
        synchronized(sshLock) {
            try {
                shellChannel?.close()
            } catch (e: Exception) {}
            shellChannel = null
            try {
                sftpClient?.close()
            } catch (e: Exception) {}
            sftpClient = null
            try {
                sshSession?.disconnect()
            } catch (e: Exception) {}
            sshSession = null
        }
        terminalSessionState.value = null
        sftpViewModelState.value = null
    }

    override fun onDestroy() {
        cleanupConnection()
        super.onDestroy()
    }
}

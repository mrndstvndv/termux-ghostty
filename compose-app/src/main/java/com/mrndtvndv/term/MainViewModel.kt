package com.mrndtvndv.term

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinator
import com.mrndtvndv.term.server.ServerRepository
import com.mrndtvndv.term.server.WorkspaceState
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import kotlinx.coroutines.launch

data class MainUiState(
    val screen: ScreenState = ScreenState.ServerList,
    val isLoading: Boolean = false,
    val error: String? = null,
    val activeTab: WorkspaceTab = WorkspaceTab.Terminal,
    val browserUrl: String = "",
)

sealed interface ScreenState {
    data object ServerList : ScreenState
    data class TerminalWorkspace(val serverId: String) : ScreenState
}

class MainViewModel(
    private val serverRepository: ServerRepository,
    val coordinator: ServerCoordinator,
    val userPrefs: UserPrefs,
    val notificationState: NotificationState,
) : ViewModel() {

    companion object {
        private const val LOCAL_TERMINAL_ID = "local_terminal"
    }

    private val _savedServers = mutableStateOf<List<ServerConfig>>(emptyList())
    val savedServers: State<List<ServerConfig>> = _savedServers

    private val _uiState = mutableStateOf(MainUiState())
    val uiState: State<MainUiState> = _uiState

    init {
        reloadServers()
    }

    // ── Server management ─────────────────────────────────────────────

    fun reloadServers() {
        _savedServers.value = serverRepository.loadAll()
    }

    fun saveServer(config: ServerConfig) {
        serverRepository.add(config)
        reloadServers()
    }

    fun deleteServer(id: String) {
        coordinator.disconnect(id)    // cleans up VM cache + ServerManager
        serverRepository.remove(id)
        reloadServers()
    }

    fun getServer(serverId: String): Server? = coordinator.getServer(serverId)
    fun getSftpViewModel(serverId: String): SftpViewModel? = coordinator.getSftpViewModel(serverId)
    fun getReviewViewModel(serverId: String): ReviewViewModel? = coordinator.getReviewViewModel(serverId)

    // ── Connection ────────────────────────────────────────────────────

    fun connect(id: String) {
        val config = serverRepository.get(id) ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = coordinator.connect(id)
            result.fold(
                onSuccess = { server ->
                    val browserUrl = when (val ws = server.workspaceState) {
                        is WorkspaceState.Tracked -> ws.tracker.browserUrl
                        is WorkspaceState.Untracked -> ""
                    }
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        browserUrl = browserUrl,
                        screen = ScreenState.TerminalWorkspace(id),
                    )
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = error.message,
                    )
                },
            )
        }
    }

    /**
     * Start a local terminal shell.
     * Uses a singleton config with a well-known ID so tapping "Local Terminal"
     * always reuses the same entry (no duplicates in saved servers).
     */
    fun startLocalTerminal() {
        var config = serverRepository.get(LOCAL_TERMINAL_ID)
        if (config == null) {
            config = ServerConfig(
                id = LOCAL_TERMINAL_ID,
                label = "Local Terminal",
                isLocal = true,
            )
            saveServer(config)
        }
        connect(config.id)
    }

    fun navigateBack() {
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
    }

    fun disconnect(id: String) {
        coordinator.disconnect(id)
        _uiState.value = _uiState.value.copy(screen = ScreenState.ServerList)
    }

    // ── Workspace tracking ────────────────────────────────────────────

    fun refreshWorkspace(serverId: String) {
        viewModelScope.launch {
            val change = coordinator.refreshWorkspace(serverId)
            if (change != null) {
                _uiState.value = _uiState.value.copy(browserUrl = change.browserUrl)
            }
        }
    }

    fun onDirectoryChanged(serverId: String, path: String) {
        coordinator.onDirectoryChanged(serverId, path)
    }

    fun onBrowserUrlChanged(serverId: String, url: String) {
        _uiState.value = _uiState.value.copy(browserUrl = url)
        coordinator.onBrowserUrlChanged(serverId, url)
    }

    // ── Tab & URL ─────────────────────────────────────────────────────

    fun setTab(tab: WorkspaceTab) {
        _uiState.value = _uiState.value.copy(activeTab = tab)
    }

    fun setBrowserUrl(url: String) {
        _uiState.value = _uiState.value.copy(browserUrl = url)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────

    override fun onCleared() {
        coordinator.disconnectAll()   // cancels in-flight + cleans VMs + disconnects servers
        super.onCleared()             // super last
    }
}

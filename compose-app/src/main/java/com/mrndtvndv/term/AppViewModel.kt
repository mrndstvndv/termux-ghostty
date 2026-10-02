package com.mrndtvndv.term

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.navigation.AppNavigator
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepositoryAccess
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerListUiState(
    val servers: List<ServerConfig> = emptyList(),
    val activeIds: Set<String> = emptySet(),
    val connectingId: String? = null,
    val disconnectingId: String? = null,
    val error: String? = null,
    val localConfig: ServerConfig? = null,
)

@Suppress("TooManyFunctions")
class AppViewModel(
    private val sessionManager: AppSessionManagerAccess,
    val notificationState: NotificationState,
    private val lastSessionStore: LastSessionStore,
) : ViewModel() {

    private val coordinator: ServerCoordinatorAccess get() = sessionManager.coordinator
    private val serverRepository: ServerRepositoryAccess get() = sessionManager.serverRepository
    private val connectionStores = ConnectionViewModelStores()

    val navigator = AppNavigator(onChanged = ::persistLastSession)

    private val _serverListState = MutableStateFlow(ServerListUiState())
    val serverListState: StateFlow<ServerListUiState> = _serverListState.asStateFlow()

    init {
        reloadServers()
        _serverListState.update { it.copy(activeIds = coordinator.activeIds) }
        observeSessionFinishes()
        restoreLastSession()
    }

    /**
     * Sessions outlive this ViewModel (owned by the app-scoped
     * AppSessionManager), so when one finishes on its own we only need to
     * reflect that in the UI.
     */
    private fun observeSessionFinishes() {
        viewModelScope.launch {
            sessionManager.sessionFinished.collect { serverId ->
                if (coordinator.getServer(serverId) != null) return@collect
                _serverListState.update { it.copy(activeIds = it.activeIds - serverId) }
                navigator.closeWorkspace(serverId)
                connectionStores.clear(serverId)
            }
        }
    }

    /**
     * If the app was previously in a terminal workspace (process killed in
     * background, or ViewModel destroyed), reconnect to that server so the
     * user returns to their last session instead of the server list.
     *
     * If the session is still alive in the app-scoped manager (dismissed
     * from recents), connect() is a no-op re-attach: the live session is
     * returned immediately without a new SSH connection.
     */
    private fun restoreLastSession() {
        val last = lastSessionStore.load() ?: return
        if (serverRepository.get(last.serverId) == null) return
        connect(last.serverId, last.activeTab)
    }

    private fun persistLastSession() {
        val serverId = navigator.currentWorkspaceId
        val tab = serverId?.let { navigator.workspace(it)?.currentTab } ?: WorkspaceTab.Terminal
        lastSessionStore.save(serverId, tab)
    }

    // ── Server management ─────────────────────────────────────────────

    private fun reloadServers() {
        val servers = serverRepository.loadAll()
        _serverListState.update {
            it.copy(servers = servers, localConfig = servers.find { config -> config.id == LOCAL_TERMINAL_ID })
        }
    }

    fun saveServer(config: ServerConfig) {
        serverRepository.add(config)
        reloadServers()
    }

    fun updateServer(config: ServerConfig) {
        serverRepository.update(config)
        reloadServers()
    }

    fun deleteServer(id: String) {
        sessionManager.disconnect(id)
        _serverListState.update { it.copy(activeIds = it.activeIds - id) }
        navigator.closeWorkspace(id)
        connectionStores.clear(id)
        serverRepository.remove(id)
        reloadServers()
    }

    fun getServer(serverId: String): Server? = coordinator.getServer(serverId)

    fun connectionStore(serverId: String): ViewModelStoreOwner = connectionStores.owner(serverId)

    /**
     * Handle a tapped terminal notification by selecting its terminal session and,
     * when available, focusing the Herdr target from the notification body and title.
     */
    fun focusTerminalNotification(
        serverId: String?,
        body: String?,
        title: String? = null,
    ) {
        val targetServerId = serverId ?: return
        val server = coordinator.getServer(targetServerId)
        if (server != null) {
            openWorkspace(server, WorkspaceTab.Terminal)
        } else {
            connect(targetServerId)
        }

        viewModelScope.launch {
            coordinator.focusHerdr(targetServerId) { focusFromNotification(body, title) }
        }
    }

    /**
     * Update configuration for the local terminal (startup command and upload settings).
     * Creates the config first if it doesn't exist yet.
     */
    fun updateLocalConfig(
        command: String,
        imagePasteEnabled: Boolean = false,
        imagePasteDirectory: String? = null,
        imagePasteAutoCleanup: Boolean = true,
        maxFiles: Int = 20,
    ) {
        val trimmed = command.trim()
        val existing = serverRepository.get(LOCAL_TERMINAL_ID)
        val updated = (existing ?: ServerConfig(
            id = LOCAL_TERMINAL_ID,
            label = "Local Terminal",
            isLocal = true,
        )).copy(
            startupCommand = trimmed.ifEmpty { null },
            imagePasteEnabled = imagePasteEnabled,
            imagePasteDirectory = imagePasteDirectory?.trim()?.ifEmpty { null },
            imagePasteAutoCleanup = imagePasteAutoCleanup,
            imagePasteMaxFiles = maxFiles.coerceAtLeast(1),
        )
        if (existing != null) {
            serverRepository.update(updated)
        } else {
            serverRepository.add(updated)
        }
        reloadServers()
    }

    /**
     * Update the startup command for the local terminal.
     * Creates the config first if it doesn't exist yet.
     */
    fun setLocalStartupCommand(command: String) {
        val existing = serverRepository.get(LOCAL_TERMINAL_ID)
        updateLocalConfig(
            command = command,
            imagePasteEnabled = existing?.imagePasteEnabled ?: false,
            imagePasteDirectory = existing?.imagePasteDirectory,
            imagePasteAutoCleanup = existing?.imagePasteAutoCleanup ?: true,
            maxFiles = existing?.safeImagePasteMaxFiles ?: ServerConfig.DEFAULT_IMAGE_PASTE_MAX_FILES,
        )
    }

    // ── Connection ────────────────────────────────────────────────────

    fun connect(id: String, initialTab: WorkspaceTab = WorkspaceTab.Terminal) {
        if (serverRepository.get(id) == null) return
        _serverListState.update { it.copy(connectingId = id, error = null) }

        viewModelScope.launch {
            val result = sessionManager.connect(id)
            result.fold(
                onSuccess = { server ->
                    _serverListState.update {
                        it.copy(connectingId = null, activeIds = it.activeIds + id)
                    }
                    openWorkspace(server, initialTab)
                },
                onFailure = { error ->
                    _serverListState.update { it.copy(connectingId = null, error = error.message) }
                    // Don't retry a dead connection on every launch.
                    persistLastSession()
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

    fun disconnect(id: String) {
        _serverListState.update { it.copy(disconnectingId = id) }
        sessionManager.disconnect(id)
        _serverListState.update { it.copy(disconnectingId = null, activeIds = it.activeIds - id) }
        navigator.closeWorkspace(id)
        connectionStores.clear(id)
    }

    // ── Workspace ─────────────────────────────────────────────────────

    private fun openWorkspace(server: Server, initialTab: WorkspaceTab) {
        val serverId = server.config.id
        val tabs = buildList {
            add(WorkspaceTab.Terminal)
            if (!server.config.isLocal) {
                if (server.tracker != null) add(WorkspaceTab.Review)
                if (server.sftpClient != null) add(WorkspaceTab.Sftp)
            }
        }
        val initialSftpDir = server.tracker?.workspaceDir?.value ?: "/"
        navigator.openWorkspace(serverId, initialTab, tabs, initialSftpDir) { tab ->
            persistLastSession()
            if (tab != WorkspaceTab.Terminal) refreshWorkspace(serverId)
        }
    }

    /**
     * Syncs with the Herdr workspace. Only a workspace change moves the SFTP
     * directory; otherwise the user's manual navigation is left alone.
     */
    private fun refreshWorkspace(serverId: String) {
        viewModelScope.launch {
            coordinator.refreshWorkspace(serverId)?.let { change ->
                navigator.workspace(serverId)?.resetSftp(change.workspaceDir)
            }
        }
    }

    override fun onCleared() {
        connectionStores.clearAll()
    }

    private companion object {
        const val LOCAL_TERMINAL_ID = "local_terminal"
    }
}

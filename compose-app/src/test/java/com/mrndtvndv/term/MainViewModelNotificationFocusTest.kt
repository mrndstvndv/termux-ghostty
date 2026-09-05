package com.mrndtvndv.term

import android.content.SharedPreferences
import com.mrndtvndv.term.data.prefs.LastSessionStore
import com.mrndtvndv.term.domain.ServerConfig
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import com.mrndtvndv.term.server.AppSessionManagerAccess
import com.mrndtvndv.term.server.Server
import com.mrndtvndv.term.server.ServerCoordinatorAccess
import com.mrndtvndv.term.server.ServerRepositoryAccess
import com.mrndtvndv.term.server.TerminalProgress
import com.mrndtvndv.term.ui.notification.NotificationState
import com.mrndtvndv.term.ui.prefs.UserPrefs
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelNotificationFocusTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `notification without a server does nothing`() = runTest(testDispatcher) {
        val ssh = RecordingSshSession()
        val server = server("server-a", ssh = ssh)
        val preferences = MemorySharedPreferences()
        val viewModel = viewModelFor(
            FakeSessionManager(
                configs = listOf(server.config),
                connectedServers = listOf(server),
            ),
            preferences,
        )

        viewModel.focusTerminalNotification(null, "project · 1")
        advanceUntilIdle()

        assertEquals(ScreenState.ServerList, viewModel.uiState.value.screen)
        assertEquals(WorkspaceTab.Terminal, viewModel.uiState.value.activeTab)
        assertTrue(ssh.commands.isEmpty())
        assertNull(preferences.value("last_screen"))
    }

    @Test
    fun `connected notification switches sessions and focuses herdr pane`() = runTest(testDispatcher) {
        val firstServer = server("server-a", ssh = RecordingSshSession())
        val targetSsh = RecordingSshSession()
        val targetServer = server("server-b", ssh = targetSsh)
        val preferences = MemorySharedPreferences()
        val manager = FakeSessionManager(
            configs = listOf(firstServer.config, targetServer.config),
            connectedServers = listOf(firstServer, targetServer),
        )
        val viewModel = viewModelFor(manager, preferences)

        viewModel.connect(firstServer.config.id)
        advanceUntilIdle()
        viewModel.setTab(WorkspaceTab.Review)

        viewModel.focusTerminalNotification(targetServer.config.id, "project · 2 · 3")
        advanceUntilIdle()

        assertEquals(
            ScreenState.TerminalWorkspace(targetServer.config.id),
            viewModel.uiState.value.screen,
        )
        assertEquals(WorkspaceTab.Terminal, viewModel.uiState.value.activeTab)
        assertEquals(listOf(firstServer.config.id), manager.connectCalls)
        assertEquals("workspace", preferences.value("last_screen"))
        assertEquals(targetServer.config.id, preferences.value("last_server_id"))
        assertEquals("Terminal", preferences.value("last_active_tab"))
        assertEquals(1, targetSsh.commands.size)
        assertTrue(targetSsh.commands.single().contains("herdr tab focus t_2_3"))
    }

    @Test
    fun `disconnected notification reconnects and opens terminal tab`() = runTest(testDispatcher) {
        val currentServer = server("server-a", ssh = RecordingSshSession())
        val targetSsh = RecordingSshSession()
        val targetServer = server("server-b", ssh = targetSsh)
        val preferences = MemorySharedPreferences()
        val manager = FakeSessionManager(
            configs = listOf(currentServer.config, targetServer.config),
            connectedServers = listOf(currentServer),
            reconnectableServers = listOf(targetServer),
        )
        val viewModel = viewModelFor(manager, preferences)

        viewModel.connect(currentServer.config.id)
        advanceUntilIdle()
        viewModel.setTab(WorkspaceTab.Review)

        viewModel.focusTerminalNotification(targetServer.config.id, "project · 2")
        advanceUntilIdle()

        assertEquals(
            ScreenState.TerminalWorkspace(targetServer.config.id),
            viewModel.uiState.value.screen,
        )
        assertEquals(WorkspaceTab.Terminal, viewModel.uiState.value.activeTab)
        assertEquals(
            listOf(currentServer.config.id, targetServer.config.id),
            manager.connectCalls,
        )
        assertEquals(targetServer.config.id, preferences.value("last_server_id"))
        assertTrue(targetSsh.commands.isEmpty())
    }

    @Test
    fun `notification still switches when no herdr resolver is available`() = runTest(testDispatcher) {
        val server = server("server-a")
        val viewModel = viewModelFor(
            FakeSessionManager(
                configs = listOf(server.config),
                connectedServers = listOf(server),
            ),
        )

        viewModel.focusTerminalNotification(server.config.id, "project · 1")
        advanceUntilIdle()

        assertEquals(ScreenState.TerminalWorkspace(server.config.id), viewModel.uiState.value.screen)
        assertEquals(WorkspaceTab.Terminal, viewModel.uiState.value.activeTab)
    }

    @Test
    fun `notification still switches to a connected non-herdr server`() = runTest(testDispatcher) {
        val ssh = RecordingSshSession()
        val server = server("server-a", herdrEnabled = false, ssh = ssh)
        val viewModel = viewModelFor(
            FakeSessionManager(
                configs = listOf(server.config),
                connectedServers = listOf(server),
            ),
        )

        viewModel.focusTerminalNotification(server.config.id, "project · 1 · 2")
        advanceUntilIdle()

        assertEquals(ScreenState.TerminalWorkspace(server.config.id), viewModel.uiState.value.screen)
        assertEquals(WorkspaceTab.Terminal, viewModel.uiState.value.activeTab)
        assertTrue(ssh.commands.isEmpty())
    }

    @Test
    fun `non-herdr notification body only switches terminal`() = runTest(testDispatcher) {
        val ssh = RecordingSshSession()
        val server = server("server-a", ssh = ssh)
        val viewModel = viewModelFor(
            FakeSessionManager(
                configs = listOf(server.config),
                connectedServers = listOf(server),
            ),
        )

        viewModel.focusTerminalNotification(server.config.id, "SFTP Error")
        advanceUntilIdle()

        assertEquals(ScreenState.TerminalWorkspace(server.config.id), viewModel.uiState.value.screen)
        assertTrue(ssh.commands.isEmpty())
    }

    @Test
    fun `external disconnect burst returns workspace to server list`() = runTest(testDispatcher) {
        val serverA = server("server-a", ssh = RecordingSshSession())
        val serverB = server("server-b", ssh = RecordingSshSession())
        val manager = FakeSessionManager(
            configs = listOf(serverA.config, serverB.config),
            connectedServers = listOf(serverA, serverB),
        )
        val viewModel = viewModelFor(manager)

        viewModel.connect(serverA.config.id)
        advanceUntilIdle()
        assertEquals(
            ScreenState.TerminalWorkspace(serverA.config.id),
            viewModel.uiState.value.screen,
        )

        // Simulate AppSessionManager.disconnectAll(): coordinator cleared first,
        // then one finish event per server (close() suppresses onSessionFinished).
        manager.coordinator.disconnect(serverA.config.id)
        manager.coordinator.disconnect(serverB.config.id)
        assertTrue(manager.sessionFinished.tryEmit(serverA.config.id))
        assertTrue(manager.sessionFinished.tryEmit(serverB.config.id))
        advanceUntilIdle()

        assertEquals(ScreenState.ServerList, viewModel.uiState.value.screen)
        assertTrue(viewModel.activeIds.value.isEmpty())
    }

    private fun viewModelFor(
        manager: FakeSessionManager,
        preferences: MemorySharedPreferences = MemorySharedPreferences(),
    ): MainViewModel = MainViewModel(
        sessionManager = manager,
        userPrefs = UserPrefs(),
        notificationState = NotificationState(),
        lastSessionStore = LastSessionStore(preferences),
    )

    private fun server(
        id: String,
        herdrEnabled: Boolean = true,
        ssh: RecordingSshSession? = null,
    ): Server {
        val config = ServerConfig(
            id = id,
            label = id,
            host = "$id.example",
            username = "user",
            herdrEnabled = herdrEnabled,
        )
        return Server(
            config = config,
            terminalSession = TerminalSession(4096, null, NoOpIo()),
            sshSession = ssh,
        )
    }

    private class FakeSessionManager(
        configs: List<ServerConfig>,
        connectedServers: List<Server>,
        reconnectableServers: List<Server> = emptyList(),
    ) : AppSessionManagerAccess {
        private val servers = connectedServers.associateBy { it.config.id }.toMutableMap()
        private val reconnectable = reconnectableServers.associateBy { it.config.id }

        override val coordinator: ServerCoordinatorAccess = FakeCoordinator(servers)
        override val serverRepository: ServerRepositoryAccess = FakeRepository(configs)
        override val sessionFinished = MutableSharedFlow<String>(extraBufferCapacity = 8)
        val connectCalls = mutableListOf<String>()
        private val progress = MutableStateFlow<TerminalProgress?>(null)

        override suspend fun connect(id: String): Result<Server> {
            connectCalls += id
            val server = servers[id] ?: reconnectable[id]
                ?: return Result.failure(IllegalStateException("Unable to connect: $id"))
            servers[id] = server
            return Result.success(server)
        }

        override fun observeTerminalProgress(session: TerminalSession): StateFlow<TerminalProgress?> =
            progress
    }

    private class FakeCoordinator(
        private val servers: MutableMap<String, Server>,
    ) : ServerCoordinatorAccess {
        override val activeIds: Set<String>
            get() = servers.keys.toSet()

        override fun getServer(id: String): Server? = servers[id]

        override fun getSftpViewModel(id: String): SftpViewModel? = null

        override fun getReviewViewModel(id: String): ReviewViewModel? = null

        override fun disconnect(id: String) {
            servers.remove(id)
        }

        override suspend fun refreshWorkspace(serverId: String) = null

        override fun onDirectoryChanged(serverId: String, path: String) = Unit
    }

    private class FakeRepository(
        configs: List<ServerConfig>,
    ) : ServerRepositoryAccess {
        private val configsById = configs.associateBy { it.id }.toMutableMap()

        override fun loadAll(): List<ServerConfig> = configsById.values.toList()

        override fun add(config: ServerConfig) {
            configsById[config.id] = config
        }

        override fun update(config: ServerConfig) {
            configsById[config.id] = config
        }

        override fun remove(id: String) {
            configsById.remove(id)
        }

        override fun get(id: String): ServerConfig? = configsById[id]
    }

    private class RecordingSshSession : SshSession {
        override val isConnected = MutableStateFlow(true)
        val commands = mutableListOf<String>()

        override suspend fun connect(config: SshConfig) = Unit

        override suspend fun authenticate(auth: SshAuth) = Unit

        override suspend fun openShellChannel(
            termType: String,
            cols: Int,
            rows: Int,
            herdrIntegration: Boolean,
        ): SshShellChannel = error("Not used by this test")

        override suspend fun openSftpClient(): SftpClient = error("Not used by this test")

        override suspend fun execCommand(command: String): String {
            commands += command
            return ""
        }

        override fun disconnect() = Unit
    }

    private class NoOpIo : TerminalSessionIO {
        override fun write(data: ByteArray?, offset: Int, count: Int) = Unit

        override fun onResize(columns: Int, rows: Int, cellWidth: Int, cellHeight: Int) = Unit

        override fun onClose() = Unit
    }

    private class MemorySharedPreferences : SharedPreferences {
        private val values = mutableMapOf<String, Any?>()

        fun value(key: String): String? = values[key] as? String

        override fun getAll(): Map<String, *> = values.toMap()

        override fun getString(key: String?, defValue: String?): String? =
            values[key] as? String ?: defValue

        override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? =
            (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            values[key] as? Float ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            values[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = Editor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        private inner class Editor : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyValue(key, value)

            override fun putStringSet(
                key: String?,
                values: Set<String>?,
            ): SharedPreferences.Editor = applyValue(key, values)

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyValue(key, value)

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyValue(key, value)

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyValue(key, value)

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyValue(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                values.remove(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                values.clear()
                return this
            }

            override fun commit(): Boolean = true

            override fun apply() = Unit

            private fun applyValue(key: String?, value: Any?): SharedPreferences.Editor {
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key.orEmpty()] = value
                }
                return this
            }
        }
    }
}

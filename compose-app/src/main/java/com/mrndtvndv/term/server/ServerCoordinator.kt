package com.mrndtvndv.term.server

import kotlinx.coroutines.Job

/**
 * Coordinates connection lifecycle and workspace tracking.
 *
 * Sits above ServerManager, adding workspace awareness
 * that ServerManager shouldn't know about.
 */
class ServerCoordinator(
    private val serverManager: ServerManager,
    private val serverRepository: ServerRepository,
) : ServerCoordinatorAccess {
    // Track in-flight connections to allow cancellation on disconnectAll
    private val connectJobs = mutableMapOf<String, Job>()

    private val herdrFrameSynchronizer = HerdrTerminalFrameSynchronizer { serverId ->
        serverManager.get(serverId)?.terminalSession?.requestGhosttyFullSnapshotRefresh()
    }

    /**
     * Connect to a server. Returns the Server on success, or failure result.
     * If already connected, returns the existing Server.
     */
    @Suppress("TooGenericExceptionCaught") // catches SSH/IO errors into Result; specific types are impractical here
    suspend fun connect(id: String): Result<Server> {
        val config = serverRepository.get(id)
            ?: return Result.failure(IllegalArgumentException("Unknown server: $id"))

        return try {
            Result.success(serverManager.connect(config))
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getServer(id: String): Server? = serverManager.get(id)

    override val activeIds: Set<String> get() = serverManager.activeIds

    override fun disconnect(id: String) {
        connectJobs[id]?.cancel()
        connectJobs.remove(id)
        serverManager.disconnect(id)
    }

    fun disconnectAll() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        serverManager.disconnectAll()
    }

    /** Returns new workspace dir if workspace changed, null otherwise. */
    override suspend fun refreshWorkspace(serverId: String): WorkspaceChange? {
        val tracker = workspaceTracker(serverId) ?: return null
        val result = tracker.sync()
        if (result !is WorkspaceTracker.SyncResult.WorkspaceChanged) return null
        return WorkspaceChange(
            workspaceDir = result.workspaceDir,
        )
    }

    private fun workspaceTracker(serverId: String): WorkspaceTracker? {
        val server = serverManager.get(serverId) ?: return null
        // Local sessions have no workspace tracker — nothing to refresh
        if (server.config.isLocal) return null
        return server.tracker
    }

    /**
     * Notify the tracker that the user navigated in SFTP.
     * Called by SftpViewModel.onDirectoryChanged (wired by the workspace route).
     */
    override fun onDirectoryChanged(serverId: String, path: String) {
        serverManager.get(serverId)?.tracker?.onDirectoryChanged(path)
    }

    /**
     * Runs a best-effort Herdr focus operation. UI nodes and notification bodies can
     * outlive their workspace/pane, so a failed focus must never crash the app.
     */
    override suspend fun focusHerdr(
        serverId: String,
        operation: suspend HerdrWorkspaceResolver.() -> Boolean,
    ) {
        val resolver = serverManager.get(serverId)?.herdrResolver() ?: return
        runCatching { herdrFrameSynchronizer.focus(serverId) { resolver.operation() } }
    }

    data class WorkspaceChange(
        val workspaceDir: String,
    )
}

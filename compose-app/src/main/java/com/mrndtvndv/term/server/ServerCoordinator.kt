package com.mrndtvndv.term.server

import androidx.lifecycle.SavedStateHandle
import com.mrndtvndv.term.ui.review.ReviewViewModel
import com.mrndtvndv.term.ui.sftp.SftpViewModel
import kotlinx.coroutines.Job

/**
 * Coordinates connection lifecycle, ViewModel caching, and workspace tracking.
 *
 * Sits above ServerManager, adding ViewModel lifecycle and workspace awareness
 * that ServerManager shouldn't know about.
 */
class ServerCoordinator(
    private val serverManager: ServerManager,
    private val serverRepository: ServerRepository,
) {
    private val sftpViewModels = mutableMapOf<String, SftpViewModel>()
    private val reviewViewModels = mutableMapOf<String, ReviewViewModel?>()
    // Track in-flight connections to allow cancellation on disconnectAll
    private val connectJobs = mutableMapOf<String, Job>()

    /**
     * Connect to a server. Returns the Server on success, or failure result.
     *
     * On success, creates and caches SftpViewModel and ReviewViewModel.
     * If already connected, returns existing Server without recreating VMs.
     */
    @Suppress("TooGenericExceptionCaught") // catches SSH/IO errors into Result; specific types are impractical here
    suspend fun connect(id: String): Result<Server> {
        val config = serverRepository.get(id)
            ?: return Result.failure(IllegalArgumentException("Unknown server: $id"))

        return try {
            val server = serverManager.connect(config)
            // Only create SFTP/Review VMs for SSH sessions (not local)
            if (!config.isLocal) {
                if (id !in sftpViewModels) {
                    sftpViewModels[id] = createSftpViewModel(server)
                }
                if (id !in reviewViewModels) {
                    reviewViewModels[id] = createReviewViewModel(server)
                }
            }
            Result.success(server)
        } catch (e: IllegalArgumentException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getServer(id: String): Server? = serverManager.get(id)

    val activeIds: Set<String> get() = serverManager.activeIds

    fun getSftpViewModel(id: String): SftpViewModel? = sftpViewModels[id]

    fun getReviewViewModel(id: String): ReviewViewModel? = reviewViewModels[id]

    fun disconnect(id: String) {
        connectJobs[id]?.cancel()
        connectJobs.remove(id)
        serverManager.disconnect(id)
        sftpViewModels.remove(id)
        reviewViewModels.remove(id)
    }

    fun disconnectAll() {
        connectJobs.values.forEach { it.cancel() }
        connectJobs.clear()
        serverManager.disconnectAll()
        sftpViewModels.clear()
        reviewViewModels.clear()
    }

    /** Returns new workspace dir + browserUrl if workspace changed, null otherwise. */
    suspend fun refreshWorkspace(serverId: String): WorkspaceChange? {
        val server = serverManager.get(serverId) ?: return null
        // Local sessions have no workspace tracker — nothing to refresh
        if (server.config.isLocal) return null
        val tracker = (server.workspaceState as? WorkspaceState.Tracked)?.tracker
            ?: return null
        val result = tracker.sync()
        return if (result is WorkspaceTracker.SyncResult.WorkspaceChanged) {
            sftpViewModels[serverId]?.navigateTo(result.workspaceDir)
            WorkspaceChange(
                workspaceDir = result.workspaceDir,
                browserUrl = result.browserUrl,
            )
        } else null
    }

    /**
     * Notify the tracker that the user navigated in SFTP.
     * Called by SftpViewModel.onPathChanged (wired during VM creation).
     */
    fun onDirectoryChanged(serverId: String, path: String) {
        val server = serverManager.get(serverId) ?: return
        (server.workspaceState as? WorkspaceState.Tracked)?.tracker?.onDirectoryChanged(path)
    }

    /**
     * Notify the tracker + persistence that the browser URL changed.
     */
    fun onBrowserUrlChanged(serverId: String, url: String) {
        val server = serverManager.get(serverId) ?: return
        (server.workspaceState as? WorkspaceState.Tracked)?.tracker?.onBrowserUrlChanged(url)
    }

    // ── Private helpers ──────────────────────────────────────────────

    private suspend fun createSftpViewModel(server: Server): SftpViewModel {
        val sftpClient = server.sftpClient
            ?: error("SFTP client unavailable for non-local server ${server.config.id}")
        val session = server.sshSession
            ?: error("SSH session unavailable for non-local server ${server.config.id}")
        val initialDir = when (val ws = server.workspaceState) {
            is WorkspaceState.Tracked -> ws.tracker.workspaceDir.value
            is WorkspaceState.Untracked -> "/"
        }
        val sftpVM = SftpViewModel(
            client = sftpClient,
            savedStateHandle = SavedStateHandle(),
            initialPath = initialDir,
            execCommand = { cmd -> session.execCommand(cmd) },
        )
        // Wire onPathChanged back into workspace tracking.
        // The serverId is captured from the caller context (map key).
        sftpVM.onPathChanged = { path ->
            val sid = sftpViewModels.entries.firstOrNull { it.value === sftpVM }?.key
            if (sid != null) onDirectoryChanged(sid, path)
        }
        return sftpVM
    }

    private suspend fun createReviewViewModel(server: Server): ReviewViewModel? {
        val tracker = (server.workspaceState as? WorkspaceState.Tracked)?.tracker ?: return null
        val session = server.sshSession
            ?: error("SSH session unavailable for non-local server ${server.config.id}")
        return ReviewViewModel(
            execCommand = { cmd -> session.execCommand(cmd) },
            workspaceDir = tracker.workspaceDir,
        )
    }

    data class WorkspaceChange(
        val workspaceDir: String,
        val browserUrl: String,
    )
}

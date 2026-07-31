package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.ScreenState
import com.mrndtvndv.term.ui.workspace.WorkspaceTab

/**
 * Persists the last visible screen so a cold start (process killed in the
 * background, activity + ViewModel destroyed) can restore the previous session.
 *
 * Only a terminal workspace is worth restoring; the server list is the
 * default landing state and needs no resurrection.
 */
class LastSessionStore(
    private val prefs: SharedPreferences,
) {
    companion object {
        private const val KEY_SCREEN = "last_screen"
        private const val KEY_SERVER_ID = "last_server_id"
        private const val KEY_ACTIVE_TAB = "last_active_tab"

        private const val SCREEN_SERVER_LIST = "server_list"
        private const val SCREEN_WORKSPACE = "workspace"
    }

    fun save(screen: ScreenState, activeTab: WorkspaceTab) {
        prefs.edit()
            .putString(KEY_SCREEN, screenKey(screen))
            .putString(KEY_SERVER_ID, (screen as? ScreenState.TerminalWorkspace)?.serverId)
            .putString(KEY_ACTIVE_TAB, activeTab.title)
            .apply()
    }

    fun load(): LastSessionState? {
        if (prefs.getString(KEY_SCREEN, null) != SCREEN_WORKSPACE) return null
        val serverId = prefs.getString(KEY_SERVER_ID, null) ?: return null
        return LastSessionState(
            serverId = serverId,
            activeTab = loadActiveTab(),
        )
    }

    private fun screenKey(screen: ScreenState): String = when (screen) {
        ScreenState.ServerList -> SCREEN_SERVER_LIST
        is ScreenState.TerminalWorkspace -> SCREEN_WORKSPACE
    }

    private fun loadActiveTab(): WorkspaceTab = when (prefs.getString(KEY_ACTIVE_TAB, null)) {
        "SFTP" -> WorkspaceTab.Sftp
        "Git" -> WorkspaceTab.Review
        "Browser" -> WorkspaceTab.Browser
        else -> WorkspaceTab.Terminal
    }
}

data class LastSessionState(
    val serverId: String,
    val activeTab: WorkspaceTab,
)

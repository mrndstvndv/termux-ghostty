package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences
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

    fun save(serverId: String?, tab: WorkspaceTab) {
        prefs.edit()
            .putString(KEY_SCREEN, if (serverId != null) SCREEN_WORKSPACE else SCREEN_SERVER_LIST)
            .putString(KEY_SERVER_ID, serverId)
            .putString(KEY_ACTIVE_TAB, tab.title)
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

    private fun loadActiveTab(): WorkspaceTab {
        val title = prefs.getString(KEY_ACTIVE_TAB, null)
        return WorkspaceTab.entries.firstOrNull { it.title == title } ?: WorkspaceTab.Terminal
    }
}

data class LastSessionState(
    val serverId: String,
    val activeTab: WorkspaceTab,
)

package com.mrndtvndv.term.data.prefs

import android.content.SharedPreferences
import com.mrndtvndv.term.server.WorkspacePersistence

class SharedPreferencesWorkspacePersistence(
    private val prefs: SharedPreferences,
) : WorkspacePersistence {
    override fun loadLastDir(workspaceKey: String): String? =
        prefs.getString("sftp_last_dir_$workspaceKey", null)

    override fun saveLastDir(workspaceKey: String, path: String) =
        prefs.edit().putString("sftp_last_dir_$workspaceKey", path).apply()
}

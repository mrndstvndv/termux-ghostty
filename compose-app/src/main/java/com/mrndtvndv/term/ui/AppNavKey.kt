package com.mrndtvndv.term.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppNavKey : NavKey {
    @Serializable
    data object ServerList : AppNavKey

    @Serializable
    data object AddServer : AppNavKey

    @Serializable
    data class TerminalWorkspace(val serverId: String) : AppNavKey

    @Serializable
    data object Settings : AppNavKey
}

@Serializable
sealed interface ReviewNavKey : NavKey {
    @Serializable
    data object ChangesList : ReviewNavKey

    @Serializable
    data class FileDiff(val path: String, val isStaged: Boolean) : ReviewNavKey

    @Serializable
    data class CommitDiff(val hash: String) : ReviewNavKey
}

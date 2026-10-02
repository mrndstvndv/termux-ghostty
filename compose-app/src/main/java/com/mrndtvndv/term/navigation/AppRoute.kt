package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object ServerList : AppRoute

    @Serializable
    data object AddServer : AppRoute

    @Serializable
    data class EditServer(val serverId: String) : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data class Workspace(val serverId: String) : AppRoute

    @Serializable
    data class FileViewer(val path: String) : AppRoute
}

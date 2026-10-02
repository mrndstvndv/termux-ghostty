package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface ReviewRoute : NavKey {
    @Serializable
    data object Changes : ReviewRoute

    @Serializable
    data class FileDiff(val path: String, val isStaged: Boolean) : ReviewRoute

    @Serializable
    data class CommitDiff(val hash: String) : ReviewRoute
}

@Serializable
data class SftpFolder(val path: String) : NavKey

package com.mrndtvndv.term.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppNavKey : NavKey {
    @Serializable
    data object Dashboard : AppNavKey
    @Serializable
    data object Settings : AppNavKey
    @Serializable
    data object TerminalWorkspace : AppNavKey
}

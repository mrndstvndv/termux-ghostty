package com.mrndtvndv.term.ui

import androidx.compose.runtime.*
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.mrndtvndv.term.MainViewModel
import com.mrndtvndv.term.ui.workspace.WorkspaceTab

class Navigator(
    val backStack: NavBackStack<NavKey>,
    private val viewModel: MainViewModel
) {
    fun navigate(key: AppNavKey) {
        backStack.add(key)
    }

    fun goBack() {
        val current = backStack.lastOrNull() as? AppNavKey
        if (current is AppNavKey.TerminalWorkspace) {
            // Check if we need to switch tabs inside the workspace first
            if (viewModel.uiState.value.activeTab != WorkspaceTab.Terminal) {
                viewModel.setTab(WorkspaceTab.Terminal)
                return
            }
            // Clean up connection when leaving the workspace
            viewModel.cleanupConnection()
        }
        
        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        }
    }
}

@Composable
fun rememberAppNavigator(viewModel: MainViewModel): Navigator {
    val backStack = rememberNavBackStack(AppNavKey.Dashboard)
    return remember(backStack, viewModel) { Navigator(backStack, viewModel) }
}

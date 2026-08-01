package com.mrndtvndv.term.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import com.mrndtvndv.term.ui.workspace.WorkspaceTab

class Navigator(
    val backStack: NavBackStack<NavKey>,
    private val activeTab: () -> WorkspaceTab,
    private val onSetTab: (WorkspaceTab) -> Unit,
    private val onNavigateBack: () -> Unit,
) {
    fun navigate(key: AppNavKey) {
        backStack.add(key)
    }

    fun goBack() {
        val current = backStack.lastOrNull() as? AppNavKey
        if (current is AppNavKey.TerminalWorkspace) {
            // Check if we need to switch tabs inside the workspace first
            if (activeTab() != WorkspaceTab.Terminal) {
                onSetTab(WorkspaceTab.Terminal)
                return
            }
            // Navigate back to server list — connection stays alive
            onNavigateBack()
        }

        if (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        }
    }

    fun goBackToRoot() {
        while (backStack.size > 1) {
            backStack.removeAt(backStack.size - 1)
        }
    }
}

@Composable
fun rememberAppNavigator(
    activeTab: () -> WorkspaceTab,
    onSetTab: (WorkspaceTab) -> Unit,
    onNavigateBack: () -> Unit,
): Navigator {
    val backStack = rememberNavBackStack(AppNavKey.ServerList)
    return remember(backStack) {
        Navigator(backStack, activeTab, onSetTab, onNavigateBack)
    }
}

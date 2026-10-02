package com.mrndtvndv.term.navigation

import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.ui.workspace.WorkspaceTab

class AppNavigator(private val onChanged: () -> Unit) {
    val backStack = NavBackStack<NavKey>(AppRoute.ServerList)

    private val workspaces = mutableMapOf<String, WorkspaceNavigator>()

    val currentWorkspaceId: String?
        get() = backStack.filterIsInstance<AppRoute.Workspace>().lastOrNull()?.serverId

    fun workspace(serverId: String): WorkspaceNavigator? = workspaces[serverId]

    fun navigate(route: AppRoute) {
        backStack.add(route)
        onChanged()
    }

    fun goBack() {
        val top = backStack.lastOrNull()
        if (top is AppRoute.Workspace && workspaces[top.serverId]?.goBack() == true) return
        if (backStack.size > 1) {
            removeFrom(backStack.lastIndex)
            onChanged()
        }
    }

    fun openWorkspace(
        serverId: String,
        initialTab: WorkspaceTab,
        tabs: List<WorkspaceTab>,
        initialSftpDir: String,
        onTabSelected: (WorkspaceTab) -> Unit,
    ) {
        val existing = workspaces[serverId]
        if (existing == null) {
            workspaces[serverId] = WorkspaceNavigator(tabs, initialTab, initialSftpDir, onTabSelected)
        }
        val route = AppRoute.Workspace(serverId)
        val index = backStack.indexOf(route)
        if (index >= 0) {
            removeFrom(index + 1)
        } else {
            removeFrom(1)
            backStack.add(route)
        }
        onChanged()
        existing?.selectTab(initialTab)
    }

    fun closeWorkspace(serverId: String) {
        workspaces.remove(serverId)
        val index = backStack.indexOf(AppRoute.Workspace(serverId))
        if (index < 0) return
        removeFrom(index)
        onChanged()
    }

    private fun removeFrom(index: Int) {
        while (backStack.size > index) {
            backStack.removeAt(backStack.lastIndex)
        }
    }
}

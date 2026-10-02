package com.mrndtvndv.term.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.mrndtvndv.term.ui.workspace.WorkspaceTab

class WorkspaceNavigator(
    val tabs: List<WorkspaceTab>,
    initialTab: WorkspaceTab,
    initialSftpDir: String,
    private val onTabSelected: (WorkspaceTab) -> Unit,
) {
    var currentTab: WorkspaceTab by mutableStateOf(initialTab.takeIf { it in tabs } ?: WorkspaceTab.Terminal)
        private set

    val reviewStack = NavBackStack<NavKey>(ReviewRoute.Changes)
    val sftpStack = NavBackStack<NavKey>(SftpFolder(initialSftpDir))

    private val currentStack: NavBackStack<NavKey>?
        get() = when (currentTab) {
            WorkspaceTab.Terminal -> null
            WorkspaceTab.Review -> reviewStack
            WorkspaceTab.Sftp -> sftpStack
        }

    /** True when back only switches to the Terminal tab, so the workspace itself must not be popped. */
    val handlesTabSwitchBack: Boolean
        get() = currentStack?.let { it.size <= 1 } == true

    fun selectTab(tab: WorkspaceTab) {
        currentTab = tab
        onTabSelected(tab)
    }

    fun openDiff(route: ReviewRoute) {
        reviewStack.add(route)
    }

    fun openFolder(path: String) {
        val index = sftpStack.indexOfLast { (it as? SftpFolder)?.path == path }
        if (index < 0) {
            sftpStack.add(SftpFolder(path))
        } else {
            while (sftpStack.lastIndex > index) sftpStack.removeAt(sftpStack.lastIndex)
        }
    }

    fun resetSftp(dir: String) {
        sftpStack.clear()
        sftpStack.add(SftpFolder(dir))
    }

    fun goBack(): Boolean {
        val stack = currentStack
        return when {
            stack != null && stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            currentTab != WorkspaceTab.Terminal -> {
                selectTab(WorkspaceTab.Terminal)
                true
            }
            else -> false
        }
    }
}

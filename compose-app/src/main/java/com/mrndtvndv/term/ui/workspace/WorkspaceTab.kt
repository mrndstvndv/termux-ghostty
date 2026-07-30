package com.mrndtvndv.term.ui.workspace

sealed interface WorkspaceTab {
    object Terminal : WorkspaceTab
    object Sftp : WorkspaceTab
    object Review : WorkspaceTab
    object Browser : WorkspaceTab

    val title: String
        get() = when (this) {
            Terminal -> "Terminal"
            Sftp -> "SFTP"
            Review -> "Git"
            Browser -> "Browser"
        }
}

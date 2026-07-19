package com.mrndtvndv.term.server

sealed interface WorkspaceState {
    /**
     * Herdr is off for this server.
     * SFTP tab starts at "/". No per-workspace state is persisted.
     * The user's manual navigation in SFTP is local to that session only.
     */
    data object Untracked : WorkspaceState

    /**
     * Herdr is on for this server.
     * WorkspaceTracker handles workspace key resolution,
     * cwd detection, and persisted SFTP directory + browser URL.
     */
    data class Tracked(val tracker: WorkspaceTracker) : WorkspaceState
}

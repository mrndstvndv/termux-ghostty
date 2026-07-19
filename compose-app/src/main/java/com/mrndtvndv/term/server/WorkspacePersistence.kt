package com.mrndtvndv.term.server

/**
 * Abstracts SharedPreferences access for per-workspace state.
 * One implementation backed by SharedPreferences (Android),
 * one fake for testing.
 */
interface WorkspacePersistence {
    fun loadLastDir(workspaceKey: String): String?
    fun saveLastDir(workspaceKey: String, path: String)
    fun loadLastUrl(workspaceKey: String): String?
    fun saveLastUrl(workspaceKey: String, url: String)
}

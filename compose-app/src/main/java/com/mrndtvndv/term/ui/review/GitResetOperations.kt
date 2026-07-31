package com.mrndtvndv.term.ui.review

import kotlinx.coroutines.flow.StateFlow

/**
 * Runs `git reset` operations against the workspace repository.
 * Returns null on success, or an error message on failure.
 */
class GitResetOperations(
    private val execCommand: suspend (String) -> String,
    private val workspaceDir: StateFlow<String>
) {
    suspend fun softReset(commit: GitCommit): String? = reset(commit, "--soft")

    suspend fun hardReset(commit: GitCommit): String? = reset(commit, "--hard")

    private suspend fun reset(commit: GitCommit, mode: String): String? {
        val repoRoot = getRepoRoot(execCommand, workspaceDir.value)
        val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
            "cd ${shellQuote(repoRoot)} && " +
            "git reset $mode ${shellQuote(commit.hash)} 2>&1; " +
            "resetExitCode=\$?; " +
            "printf '\\n$RESET_EXIT_MARKER%s\\n' \"\$resetExitCode\""
        val output = execCommand(command)
        val markerIndex = output.lastIndexOf(RESET_EXIT_MARKER)
        val exitCode = if (markerIndex >= 0) {
            output.substring(markerIndex + RESET_EXIT_MARKER.length)
                .lineSequence()
                .firstOrNull()
                ?.trim()
                ?.toIntOrNull()
        } else {
            null
        }
        val details = if (markerIndex >= 0) {
            output.substring(0, markerIndex).trim()
        } else {
            output.trim()
        }

        if (exitCode == 0) return null
        val suffix = details.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()
        return "git reset $mode failed$suffix"
    }

    private companion object {
        const val RESET_EXIT_MARKER = "__REVIEW_RESET_EXIT__"
    }
}

internal suspend fun getRepoRoot(execCommand: suspend (String) -> String, dir: String): String {
    return runCatching {
        val command = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
            "cd \"$dir\" && git rev-parse --show-toplevel"
        val output = execCommand(command).trim()
        if (output.isNotEmpty() && !output.startsWith("fatal:") && !output.contains("error")) {
            output
        } else {
            dir
        }
    }.getOrDefault(dir)
}

internal fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

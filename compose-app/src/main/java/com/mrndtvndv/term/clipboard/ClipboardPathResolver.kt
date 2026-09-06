package com.mrndtvndv.term.clipboard

import com.mrndtvndv.term.domain.SshSession
import java.io.File

object ClipboardPathResolver {
    const val DEFAULT_IMAGE_CACHE_DIR = "~/.cache/termux-ghostty"

    /**
     * Resolves the remote target directory from custom user input.
     * Returns null if no path is provided.
     */
    fun resolveRemoteTargetDir(customRemoteDir: String?): String? {
        return customRemoteDir?.trim()?.takeIf { it.isNotEmpty() && '\u0000' !in it }
    }

    /** Quotes one value for use as a single POSIX shell argument. */
    internal fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"

    /** Builds the only shell command used to expand and canonicalize a remote path. */
    internal fun buildRemoteDirectoryCommand(targetDir: String): String =
        "target=${shellQuote(targetDir)}; " +
            "case \"\$target\" in \"~\"*) target=\"\$HOME\${target#\\~}\" ;; esac; " +
            "mkdir -p -- \"\$target\" && cd -- \"\$target\" && pwd -P"

    /**
     * Resolves the remote directory on the remote SSH host by expanding leading ~ or ~/
     * via POSIX shell expansion, creating the directory, and querying canonical `pwd -P`.
     * Returns null if no customRemoteDir is provided or directory resolution fails.
     */
    suspend fun resolveRemoteDir(ssh: SshSession, customRemoteDir: String?): String? {
        val targetDir = resolveRemoteTargetDir(customRemoteDir) ?: return null
        val pwdOutput = ssh.execCommand(buildRemoteDirectoryCommand(targetDir)).trim()
        return pwdOutput.lines().lastOrNull { it.startsWith("/") }?.trim()
    }

    /**
     * Resolves local path string, expanding leading ~ or ~/ to the user's home directory.
     * Returns null if no path is provided.
     */
    fun resolveLocalTargetPath(customDirectory: String?, homeOverride: String? = null): String? {
        val raw = customDirectory?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return expandLocalHome(raw, homeOverride)
    }

    /**
     * Resolves the local target directory for clipboard images.
     * Returns null if no customDirectory is provided.
     */
    fun resolveLocalDirectory(customDirectory: String? = null, homeOverride: String? = null): File? {
        val customPath = resolveLocalTargetPath(customDirectory, homeOverride) ?: return null
        val custom = File(customPath)
        custom.mkdirs()
        return custom
    }

    internal fun expandLocalHome(path: String, homeOverride: String? = null): String {
        if (path == "~" || path.startsWith("~/")) {
            val home = homeOverride
                ?: System.getenv("HOME")
                ?: System.getProperty("user.home")
                ?: ""
            if (home.isNotEmpty()) {
                val suffix = if (path == "~") "" else path.removePrefix("~")
                return "${home.removeSuffix("/")}$suffix"
            }
        }
        return path
    }
}

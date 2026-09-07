package com.mrndtvndv.term.clipboard

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SshSession
import kotlinx.coroutines.CancellationException
import java.io.File

object ClipboardPathResolver {
    private val homePrefixes = listOf("%userprofile%", "\$env:userprofile", "\$home")
    private val windowsDrivePath = Regex("^/?[A-Za-z]:(/.*)?\\z")
    const val DEFAULT_IMAGE_CACHE_DIR = "~/.cache/termux-ghostty"

    /**
     * Resolves the remote target directory from custom user input.
     * Returns null if no path is provided.
     */
    fun resolveRemoteTargetDir(customRemoteDir: String?): String? {
        return customRemoteDir?.trim()?.takeIf { it.isNotEmpty() && '\u0000' !in it }
    }

    /**
     * Queries the remote home directory using environment-variable syntax supported by
     * POSIX shells, PowerShell, and cmd.exe. Returns null when the home cannot be
     * determined (no SSH session, exec failure, or an unsupported shell).
     */
    internal suspend fun resolveRemoteHome(ssh: SshSession?): String? {
        if (ssh == null) return null
        for (command in listOf("echo \$HOME", "echo %USERPROFILE%")) {
            val home = try {
                sanitizeRemoteHome(ssh.execCommand(command))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (home != null) return home
        }
        return null
    }

    internal fun sanitizeRemoteHome(rawOutput: String): String? {
        val home = rawOutput.lineSequence()
            .map(String::trim)
            .lastOrNull { it.isNotEmpty() && '\u0000' !in it && ('/' in it || '\\' in it) }
            ?: return null
        val normalized = home.replace('\\', '/').trimEnd('/')
        return normalized.ifEmpty { "/" }
    }

    private fun stripHomePrefix(path: String): String? {
        if (path == "~") return ""
        if (path.startsWith("~/")) return path.removePrefix("~/")
        val lower = path.lowercase()
        val prefix = homePrefixes.firstOrNull { lower == it || lower.startsWith("$it/") }
        return prefix?.let { if (lower == it) "" else path.substring(it.length + 1) }
    }

    /**
     * Normalizes a remote target directory without involving the remote shell, so it works
     * regardless of whether the server runs sh/bash (Linux/macOS) or PowerShell (Windows):
     * backslashes become forward slashes (accepted by OpenSSH's SFTP server on Windows and
     * pasted verbatim into PowerShell), and home-relative forms (`~`, `%USERPROFILE%`,
     * `$env:USERPROFILE`, `$HOME`) expand via [home]. A home-relative path is rejected
     * when [home] cannot be determined so an upload is never written to an unexpected
     * working directory.
     * Returns null for paths that cannot be expanded (e.g. `~otheruser/...`).
     */
    internal fun normalizeRemotePath(rawTargetDir: String, home: String?): String? {
        var path = rawTargetDir.replace('\\', '/').trim().replace(Regex("/{2,}"), "/")
        val remainder = stripHomePrefix(path)
        if (remainder != null) {
            if (home.isNullOrEmpty()) return null
            val resolvedHome = home.orEmpty()
            path = if (remainder.isEmpty()) {
                resolvedHome
            } else {
                "${resolvedHome.removeSuffix("/")}/$remainder"
            }
        } else if (path.startsWith("~")) {
            return null
        }
        if (path.length > 1) path = path.removeSuffix("/")
        if (path.startsWith("/") && windowsDrivePath.matches(path)) {
            path = path.removePrefix("/")
        }
        return when {
            path.isEmpty() -> "/"
            windowsDrivePath.matches(path) -> if (path.endsWith(":")) "$path/" else path
            else -> path
        }
    }

    /** Converts a Windows shell path to the `/C:/...` form expected by SFTP servers. */
    internal fun toSftpPath(path: String): String =
        if (windowsDrivePath.matches(path) && !path.startsWith("/")) "/$path" else path

    /**
     * Builds the `mkdir -p` equivalent chain for [normalizedPath]: every ancestor from the
     * top down, so each level can be created over SFTP in order. Handles POSIX absolute
     * (`/a/b`), Windows drive (`C:/a/b`), SFTP-style drive (`/C:/a/b`), and relative paths.
     */
    internal fun remoteDirPrefixes(normalizedPath: String): List<String> {
        val segments = normalizedPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return emptyList()
        val root = if (normalizedPath.startsWith("/")) "/" else ""
        return segments.indices.map { end ->
            root + segments.subList(0, end + 1).joinToString("/")
        }
    }

    /**
     * Resolves the remote directory purely over SFTP: creates missing levels (the only
     * operation the old POSIX shell command performed) and verifies the result by listing
     * it. No remote shell is involved, so Windows servers defaulting to PowerShell work
     * the same as POSIX servers. Returns the normalized path, or null when the directory
     * cannot be created or verified.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun resolveRemoteDir(
        sftp: SftpClient,
        ssh: SshSession?,
        customRemoteDir: String?,
    ): String? {
        val targetDir = resolveRemoteTargetDir(customRemoteDir) ?: return null
        val collapsed = targetDir.replace('\\', '/').trim().replace(Regex("/{2,}"), "/")
        val home = if (stripHomePrefix(collapsed) != null) resolveRemoteHome(ssh) else null
        val normalized = normalizeRemotePath(targetDir, home) ?: return null
        val sftpPath = toSftpPath(normalized)
        var directoryExists = remoteDirectoryExists(sftp, sftpPath)
        if (!directoryExists) {
            remoteDirPrefixes(sftpPath).forEach { prefix ->
                createRemoteDirectory(sftp, prefix)
            }
            directoryExists = remoteDirectoryExists(sftp, sftpPath)
        }
        return normalized.takeIf { directoryExists }
    }

    private suspend fun remoteDirectoryExists(sftp: SftpClient, path: String): Boolean {
        return try {
            sftp.listFiles(path)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("SwallowedException")
    private suspend fun createRemoteDirectory(sftp: SftpClient, path: String) {
        try {
            sftp.createDirectory(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Another client may have created the directory already.
        }
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

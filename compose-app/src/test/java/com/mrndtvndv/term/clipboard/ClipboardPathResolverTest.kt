package com.mrndtvndv.term.clipboard

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.domain.SshAuth
import com.mrndtvndv.term.domain.SshConfig
import com.mrndtvndv.term.domain.SshSession
import com.mrndtvndv.term.domain.SshShellChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException

class ClipboardPathResolverTest {

    @Test
    fun resolveRemoteTargetDir_nullWhenNullOrEmpty() {
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir(null))
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir(""))
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir("   "))
    }

    @Test
    fun resolveRemoteTargetDir_returnsTrimmedNonEmptyPath() {
        val path = "/Users/steven/Downloads"
        assertEquals(path, ClipboardPathResolver.resolveRemoteTargetDir(path))
        assertEquals("/tmp/clipboard", ClipboardPathResolver.resolveRemoteTargetDir("/tmp/clipboard"))
        assertEquals("/var/data", ClipboardPathResolver.resolveRemoteTargetDir("  /var/data  "))
        assertEquals(
            "~/.cache/termux-ghostty",
            ClipboardPathResolver.resolveRemoteTargetDir(" ~/.cache/termux-ghostty "),
        )
    }

    @Test
    fun resolveRemoteTargetDir_rejectsNul() {
        assertNull(ClipboardPathResolver.resolveRemoteTargetDir("/tmp/unsafe\u0000path"))
    }

    @Test
    fun sanitizeRemoteHome_acceptsPosixAndWindowsHomes() {
        assertEquals("/home/anlaki", ClipboardPathResolver.sanitizeRemoteHome("/home/anlaki\n"))
        assertEquals(
            "C:/Users/anlaki",
            ClipboardPathResolver.sanitizeRemoteHome("C:\\Users\\anlaki\r\n"),
        )
        assertEquals("C:/Users/anlaki", ClipboardPathResolver.sanitizeRemoteHome("C:/Users/anlaki/"))
    }

    @Test
    fun sanitizeRemoteHome_rejectsUnexpandedOrBlankOutput() {
        assertNull(ClipboardPathResolver.sanitizeRemoteHome(""))
        assertNull(ClipboardPathResolver.sanitizeRemoteHome("\n"))
        // cmd.exe echoes the literal text instead of expanding $HOME.
        assertNull(ClipboardPathResolver.sanitizeRemoteHome("\$HOME"))
        assertNull(ClipboardPathResolver.sanitizeRemoteHome("relative"))
    }

    @Test
    fun resolveRemoteHome_fallsBackToCmdEnvironmentSyntax() = runTest {
        val ssh = FakeSshSession("\$HOME", "C:\\Users\\anlaki")
        assertEquals("C:/Users/anlaki", ClipboardPathResolver.resolveRemoteHome(ssh))
        assertEquals(listOf("echo \$HOME", "echo %USERPROFILE%"), ssh.execCommands)
    }

    @Test
    fun normalizeRemotePath_expandsTildeWithHome() {
        assertEquals(
            "/home/u/.cache/termux-ghostty",
            ClipboardPathResolver.normalizeRemotePath("~/.cache/termux-ghostty", "/home/u"),
        )
        assertEquals("/home/u", ClipboardPathResolver.normalizeRemotePath("~", "/home/u"))
        assertEquals(
            "C:/Users/a/.cache/termux-ghostty",
            ClipboardPathResolver.normalizeRemotePath("~/.cache/termux-ghostty", "C:/Users/a"),
        )
    }

    @Test
    fun normalizeRemotePath_homeRelativePathRequiresHome() {
        assertNull(ClipboardPathResolver.normalizeRemotePath("~/.cache/termux-ghostty", null))
        assertNull(ClipboardPathResolver.normalizeRemotePath("~", null))
    }

    @Test
    fun normalizeRemotePath_convertsWindowsSeparators() {
        assertEquals(
            "C:/Users/anlaki/.cache/termux-ghostty",
            ClipboardPathResolver.normalizeRemotePath(
                "C:\\Users\\anlaki\\.cache\\termux-ghostty",
                null,
            ),
        )
        assertEquals(
            "C:/Users/anlaki/.cache/termux-ghostty",
            ClipboardPathResolver.normalizeRemotePath("C:/Users//anlaki/.cache/termux-ghostty/", null),
        )
    }

    @Test
    fun normalizeRemotePath_expandsUserProfileForms() {
        assertEquals(
            "C:/Users/a/.cache/x",
            ClipboardPathResolver.normalizeRemotePath("%USERPROFILE%\\.cache\\x", "C:/Users/a"),
        )
        assertEquals(
            "C:/Users/a/.cache/x",
            ClipboardPathResolver.normalizeRemotePath("%userprofile%/.cache/x", "C:/Users/a"),
        )
        assertEquals(
            "C:/Users/a/.cache/x",
            ClipboardPathResolver.normalizeRemotePath("\$env:USERPROFILE/.cache/x", "C:/Users/a"),
        )
        assertEquals(
            "C:/Users/a/.cache/x",
            ClipboardPathResolver.normalizeRemotePath("\$HOME/.cache/x", "C:/Users/a"),
        )
        assertNull(ClipboardPathResolver.normalizeRemotePath("%USERPROFILE%/.cache/x", null))
    }

    @Test
    fun normalizeRemotePath_rejectsTildeUser() {
        assertNull(ClipboardPathResolver.normalizeRemotePath("~other/.cache/x", "/home/u"))
    }

    @Test
    fun normalizeRemotePath_keepsRoots() {
        assertEquals("/", ClipboardPathResolver.normalizeRemotePath("/", null))
        assertEquals("C:/", ClipboardPathResolver.normalizeRemotePath("C:\\", null))
        assertEquals("C:/", ClipboardPathResolver.normalizeRemotePath("/C:/", null))
        assertEquals("/tmp/x", ClipboardPathResolver.normalizeRemotePath("/tmp/x/", null))
    }

    @Test
    fun toSftpPath_usesWindowsAbsolutePathForm() {
        assertEquals(
            "/C:/Users/a/.cache/x",
            ClipboardPathResolver.toSftpPath("C:/Users/a/.cache/x"),
        )
        assertEquals("/C:/", ClipboardPathResolver.toSftpPath("C:/"))
        assertEquals("/tmp/x", ClipboardPathResolver.toSftpPath("/tmp/x"))
        assertEquals("relative/path", ClipboardPathResolver.toSftpPath("relative/path"))
    }

    @Test
    fun remoteDirPrefixes_buildsMkdirChain() {
        assertEquals(
            listOf("/a", "/a/b"),
            ClipboardPathResolver.remoteDirPrefixes("/a/b"),
        )
        assertEquals(
            listOf("C:", "C:/Users", "C:/Users/x"),
            ClipboardPathResolver.remoteDirPrefixes("C:/Users/x"),
        )
        assertEquals(
            listOf("/C:", "/C:/Users", "/C:/Users/x"),
            ClipboardPathResolver.remoteDirPrefixes("/C:/Users/x"),
        )
        assertEquals(
            listOf("rel", "rel/path"),
            ClipboardPathResolver.remoteDirPrefixes("rel/path"),
        )
        assertTrue(ClipboardPathResolver.remoteDirPrefixes("/").isEmpty())
    }

    @Test
    fun resolveRemoteDir_createsMissingChainOverSftp() = runTest {
        val sftp = FakeSftpClient()
        val resolved = ClipboardPathResolver.resolveRemoteDir(
            sftp = sftp,
            ssh = FakeSshSession("/home/u\n"),
            customRemoteDir = "~/.cache/termux-ghostty",
        )
        assertEquals("/home/u/.cache/termux-ghostty", resolved)
        assertEquals(
            listOf("/home", "/home/u", "/home/u/.cache", "/home/u/.cache/termux-ghostty"),
            sftp.created,
        )
    }

    @Test
    fun resolveRemoteDir_expandsTildeWithWindowsHome() = runTest {
        val sftp = FakeSftpClient()
        val resolved = ClipboardPathResolver.resolveRemoteDir(
            sftp = sftp,
            ssh = FakeSshSession("C:\\Users\\anlaki\r\n"),
            customRemoteDir = "~/.cache/termux-ghostty",
        )
        assertEquals("C:/Users/anlaki/.cache/termux-ghostty", resolved)
    }

    @Test
    fun resolveRemoteDir_windowsPathSkipsShellAndToleratesExistingParents() = runTest {
        val sftp = FakeSftpClient().apply {
            existing.addAll(listOf("/C:", "/C:/Users", "/C:/Users/anlaki"))
        }
        val ssh = FakeSshSession("C:\\Users\\anlaki\r\n")
        val resolved = ClipboardPathResolver.resolveRemoteDir(
            sftp = sftp,
            ssh = ssh,
            customRemoteDir = "C:\\Users\\anlaki\\.cache\\termux-ghostty",
        )
        assertEquals("C:/Users/anlaki/.cache/termux-ghostty", resolved)
        assertEquals(0, ssh.execCalls)
        assertEquals(
            listOf(
                "/C:",
                "/C:/Users",
                "/C:/Users/anlaki",
                "/C:/Users/anlaki/.cache",
                "/C:/Users/anlaki/.cache/termux-ghostty",
            ),
            sftp.created,
        )
    }

    @Test
    fun resolveRemoteDir_returnsExistingDirWithoutCreating() = runTest {
        val sftp = FakeSftpClient().apply { existing.add("/tmp/x") }
        val resolved = ClipboardPathResolver.resolveRemoteDir(sftp, null, "/tmp/x")
        assertEquals("/tmp/x", resolved)
        assertTrue(sftp.created.isEmpty())
    }

    @Test
    fun resolveRemoteDir_treatsMetacharactersAsLiteralPath() = runTest {
        val sftp = FakeSftpClient()
        val target = "/tmp/a path/'; touch /tmp/pwned; #"
        assertEquals(target, ClipboardPathResolver.resolveRemoteDir(sftp, null, target))
    }

    @Test
    fun resolveRemoteDir_returnsNullWhenHomeCannotBeResolved() = runTest {
        assertNull(
            ClipboardPathResolver.resolveRemoteDir(
                sftp = FakeSftpClient(),
                ssh = null,
                customRemoteDir = "~/.cache/termux-ghostty",
            ),
        )
    }

    @Test
    fun resolveRemoteDir_nullWhenUnverifiable() = runTest {
        val sftp = FakeSftpClient(failCreate = true)
        assertNull(ClipboardPathResolver.resolveRemoteDir(sftp, null, "/tmp/x"))
    }

    @Test
    fun resolveRemoteDir_nullWhenNoDirectory() = runTest {
        assertNull(ClipboardPathResolver.resolveRemoteDir(FakeSftpClient(), null, null))
        assertNull(ClipboardPathResolver.resolveRemoteDir(FakeSftpClient(), null, "   "))
    }

    private class FakeSftpClient(private val failCreate: Boolean = false) : SftpClient {
        val existing = mutableSetOf<String>()
        val created = mutableListOf<String>()

        override suspend fun listFiles(path: String): List<SftpFile> {
            if (path !in existing) throw IOException("No such directory: $path")
            return emptyList()
        }

        override suspend fun createDirectory(path: String) {
            created.add(path)
            if (failCreate) throw IOException("mkdir failed: $path")
            if (path in existing) throw IOException("Exists: $path")
            existing.add(path)
        }

        override suspend fun deleteFile(path: String) = Unit
        override suspend fun renameFile(oldPath: String, newPath: String) = Unit
        override suspend fun downloadFile(
            remotePath: String,
            destination: File,
            onProgress: (Long) -> Unit,
        ) = Unit
        override suspend fun uploadFile(
            source: File,
            remotePath: String,
            onProgress: (Long) -> Unit,
        ) = Unit
        override fun close() = Unit
    }

    private class FakeSshSession(private vararg val homeOutputs: String) : SshSession {
        private val connected = MutableStateFlow(true)
        var execCalls = 0
        val execCommands = mutableListOf<String>()

        override val isConnected: StateFlow<Boolean> = connected
        override suspend fun connect(config: SshConfig) = Unit
        override suspend fun authenticate(auth: SshAuth) = Unit
        override suspend fun openShellChannel(
            termType: String,
            cols: Int,
            rows: Int,
            herdrIntegration: Boolean,
        ): SshShellChannel = throw UnsupportedOperationException()
        override suspend fun openSftpClient(): SftpClient = FakeSftpClient()
        override suspend fun execCommand(command: String): String {
            execCalls++
            execCommands.add(command)
            return homeOutputs.getOrElse(execCalls - 1) { homeOutputs.lastOrNull().orEmpty() }
        }
        override fun disconnect() = Unit
    }

    @Test
    fun defaultImageCacheDir_isTildePrefixed() {
        assertEquals("~/.cache/termux-ghostty", ClipboardPathResolver.DEFAULT_IMAGE_CACHE_DIR)
    }

    @Test
    fun expandLocalHome_expandsTildeAndSubpaths() {
        val fakeHome = "/home/testuser"
        assertEquals("/home/testuser", ClipboardPathResolver.expandLocalHome("~", fakeHome))
        assertEquals("/home/testuser/foo", ClipboardPathResolver.expandLocalHome("~/foo", fakeHome))
        assertEquals(
            "/home/testuser/.cache/termux-ghostty",
            ClipboardPathResolver.expandLocalHome("~/.cache/termux-ghostty", fakeHome),
        )
        assertEquals("/absolute/path", ClipboardPathResolver.expandLocalHome("/absolute/path", fakeHome))
    }

    @Test
    fun resolveLocalTargetPath_usesTheApplicationHomeOverride() {
        assertEquals(
            "/data/user/0/app/files/.cache/termux-ghostty",
            ClipboardPathResolver.resolveLocalTargetPath(
                "~/.cache/termux-ghostty",
                "/data/user/0/app/files",
            ),
        )
    }

    @Test
    fun resolveLocalTargetPath_nullWhenNullOrEmpty() {
        assertNull(ClipboardPathResolver.resolveLocalTargetPath(null))
        assertNull(ClipboardPathResolver.resolveLocalTargetPath(""))
        assertNull(ClipboardPathResolver.resolveLocalTargetPath("   "))
    }

    @Test
    fun resolveLocalDirectory_returnsNullWhenNoCustomDirectory() {
        assertNull(ClipboardPathResolver.resolveLocalDirectory(null))
        assertNull(ClipboardPathResolver.resolveLocalDirectory(""))
        assertNull(ClipboardPathResolver.resolveLocalDirectory("   "))
        assertNotNull(ClipboardPathResolver.resolveLocalDirectory("/tmp/test_dir"))
    }
}

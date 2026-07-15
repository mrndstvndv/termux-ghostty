package com.mrndtvndv.term.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class MockSftpClient : SftpClient {
    private val mockFiles = mutableListOf(
        SftpFile("docs", "/docs", true, 0, 493, System.currentTimeMillis()),
        SftpFile("src", "/src", true, 0, 493, System.currentTimeMillis()),
        SftpFile("README.md", "/README.md", false, 1024, 420, System.currentTimeMillis()),
        SftpFile("build.gradle", "/build.gradle", false, 2048, 420, System.currentTimeMillis())
    )

    override suspend fun listFiles(path: String): List<SftpFile> {
        if (path == "/") {
            return mockFiles
        }
        val cleanPath = if (path.endsWith("/")) path.dropLast(1) else path
        return listOf(
            SftpFile("..", cleanPath.substringBeforeLast('/').ifEmpty { "/" }, true, 0, 493, System.currentTimeMillis()),
            SftpFile("subfile.txt", "$cleanPath/subfile.txt", false, 512, 420, System.currentTimeMillis())
        )
    }

    override suspend fun createDirectory(path: String) {
        mockFiles.add(SftpFile(path.substringAfterLast('/'), path, true, 0, 493, System.currentTimeMillis()))
    }

    override suspend fun deleteFile(path: String) {
        mockFiles.removeAll { it.path == path }
    }

    override suspend fun downloadFile(remotePath: String, destination: File, onProgress: (Long) -> Unit) {
        onProgress(100L)
    }

    override suspend fun uploadFile(source: File, remotePath: String, onProgress: (Long) -> Unit) {
        onProgress(100L)
    }

    override fun close() {}
}

class MockSshShellChannel : SshShellChannel {
    override val inputStream: InputStream = ByteArrayInputStream("Mock Shell Output\n$ ".toByteArray())
    override val outputStream: OutputStream = ByteArrayOutputStream()
    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {}
    override fun close() {}
}

class MockSshSession : SshSession {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    override suspend fun connect(config: SshConfig) {
        _isConnected.value = true
    }

    override suspend fun authenticate(auth: SshAuth) {
        _isConnected.value = true
    }

    override suspend fun openShellChannel(termType: String, cols: Int, rows: Int): SshShellChannel {
        return MockSshShellChannel()
    }

    override suspend fun openSftpClient(): SftpClient {
        return MockSftpClient()
    }

    override fun disconnect() {
        _isConnected.value = false
    }
}

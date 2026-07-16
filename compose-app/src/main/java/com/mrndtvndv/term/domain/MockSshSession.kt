package com.mrndtvndv.term.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

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

    override suspend fun openShellChannel(termType: String, cols: Int, rows: Int, herdrIntegration: Boolean): SshShellChannel {
        return MockSshShellChannel()
    }

    override fun disconnect() {
        _isConnected.value = false
    }
}

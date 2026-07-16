package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.data.ssh.jvm.JvmSshSession
import com.mrndtvndv.term.domain.*
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

class NativeSshSession : SshSession {
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected

    private var config: SshConfig? = null
    private var auth: SshAuth? = null
    private var socket: Socket? = null
    var nativeSessionHandle: Long = 0L
        private set

    private val jvmFallback = JvmSshSession()

    override suspend fun connect(config: SshConfig) {
        this.config = config
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(InetSocketAddress(config.host, config.port), config.connectionTimeoutMs)
            socket = s
            jvmFallback.connect(config)
        }
    }

    override suspend fun authenticate(auth: SshAuth) {
        this.auth = auth
        withContext(Dispatchers.IO) {
            jvmFallback.authenticate(auth)
            _isConnected.value = true
        }
    }

    override suspend fun openShellChannel(termType: String, cols: Int, rows: Int): SshShellChannel {
        return withContext(Dispatchers.IO) {
            val s = socket ?: throw IllegalStateException("Not connected")
            val pfd = ParcelFileDescriptor.fromSocket(s)
            val fd = pfd.detachFd()

            val currentConfig = config ?: throw IllegalStateException("Config missing")
            val currentAuth = auth ?: throw IllegalStateException("Auth missing")

            val username = currentConfig.username
            val isPassword = currentAuth is SshAuth.Password
            val passwordOrKey = when (currentAuth) {
                is SshAuth.Password -> String(currentAuth.password)
                is SshAuth.PublicKey -> currentAuth.privateKeyPem
            }

            val handle = GhosttyNative.nativeSshInit(
                fd,
                username,
                passwordOrKey,
                isPassword,
                termType,
                cols,
                rows
            )

            if (handle == 0L) {
                throw IllegalStateException("Failed to initialize native SSH session")
            }

            nativeSessionHandle = handle
            GhosttyNative.nativeSshStart(handle)

            NativeSshShellChannel(handle)
        }
    }

    override suspend fun openSftpClient(): SftpClient {
        return jvmFallback.openSftpClient()
    }

    override fun disconnect() {
        if (nativeSessionHandle != 0L) {
            GhosttyNative.nativeSshDeinit(nativeSessionHandle)
            nativeSessionHandle = 0L
        }
        try {
            socket?.close()
        } catch (e: Exception) {}
        jvmFallback.disconnect()
        _isConnected.value = false
    }
}

class NativeSshShellChannel(val nativeHandle: Long) : SshShellChannel {
    override val inputStream: InputStream = object : InputStream() {
        override fun read(): Int = -1
    }
    
    override val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) {
            val buf = ByteArray(1)
            buf[0] = b.toByte()
            write(buf, 0, 1)
        }
        
        override fun write(b: ByteArray, off: Int, len: Int) {
            GhosttyNative.nativeSshWrite(nativeHandle, b, off, len)
        }
    }

    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        GhosttyNative.nativeSshResize(nativeHandle, cols, rows)
    }

    override fun close() {
        // Session deinit handles this
    }
}

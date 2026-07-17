package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.*
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
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

    private val lock = Any()
    private var activeSftpClient: NativeSftpClient? = null

    override suspend fun connect(config: SshConfig) {
        this.config = config
        withContext(Dispatchers.IO) {
            val s = Socket()
            s.connect(InetSocketAddress(config.host, config.port), config.connectionTimeoutMs)
            socket = s
        }
    }

    override suspend fun authenticate(auth: SshAuth) {
        this.auth = auth
        withContext(Dispatchers.IO) {
            _isConnected.value = true
        }
    }

    override suspend fun openShellChannel(
        termType: String,
        cols: Int,
        rows: Int,
        herdrIntegration: Boolean
    ): SshShellChannel {
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

            synchronized(lock) {
                val handle = GhosttyNative.nativeSshInit(
                    fd,
                    username,
                    passwordOrKey,
                    isPassword,
                    termType,
                    cols,
                    rows,
                    herdrIntegration
                )

                if (handle == 0L) {
                    throw IllegalStateException("Failed to initialize native SSH session")
                }

                nativeSessionHandle = handle
                GhosttyNative.nativeSshStart(handle)

                NativeSshShellChannel(handle)
            }
        }
    }

    override suspend fun openSftpClient(): SftpClient = synchronized(lock) {
        val handle = nativeSessionHandle
        if (handle == 0L) throw IllegalStateException("Not connected")
        val sftpHandle = GhosttyNative.nativeSftpInit(handle)
        if (sftpHandle == 0L) throw IOException("Failed to initialize native SFTP subsystem")
        val client = NativeSftpClient(sftpHandle, handle)
        activeSftpClient = client
        client
    }

    override suspend fun execCommand(command: String): String {
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val handle = nativeSessionHandle
                if (handle == 0L) ""
                else GhosttyNative.nativeSshExec(handle, command) ?: ""
            }
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            activeSftpClient?.close()
            activeSftpClient = null
            val handle = nativeSessionHandle
            if (handle != 0L) {
                GhosttyNative.nativeSshDeinit(handle)
                nativeSessionHandle = 0L
            }
        }
        try {
            socket?.close()
        } catch (e: Exception) {}
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

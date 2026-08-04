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
    @Volatile
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

    @Suppress("TooGenericExceptionCaught")
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
                val handle = try {
                    GhosttyNative.nativeSshInit(
                        fd,
                        username,
                        passwordOrKey,
                        isPassword,
                        termType,
                        cols,
                        rows,
                        herdrIntegration
                    )
                } catch (e: Throwable) {
                    com.termux.terminal.JNI.close(fd)
                    throw e
                }

                if (handle == 0L) {
                    com.termux.terminal.JNI.close(fd)
                    throw IllegalStateException("Failed to initialize native SSH session")
                }

                nativeSessionHandle = handle
                GhosttyNative.nativeSshStart(handle)

                NativeSshShellChannel(this@NativeSshSession)
            }
        }
    }

    override suspend fun openSftpClient(): SftpClient = synchronized(lock) {
        val handle = nativeSessionHandle
        if (handle == 0L) throw IllegalStateException("Not connected")
        activeSftpClient?.close()
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

    internal fun writeToShell(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            val handle = nativeSessionHandle
            if (handle != 0L) {
                GhosttyNative.nativeSshWrite(handle, data, offset, length)
            }
        }
    }

    internal fun resizeShell(cols: Int, rows: Int) {
        synchronized(lock) {
            val handle = nativeSessionHandle
            if (handle != 0L) {
                GhosttyNative.nativeSshResize(handle, cols, rows)
            }
        }
    }

    override fun disconnect() {
        synchronized(lock) {
            activeSftpClient?.close()
            activeSftpClient = null
            val handle = nativeSessionHandle
            nativeSessionHandle = 0L
            if (handle != 0L) {
                GhosttyNative.nativeSshDeinit(handle)
            }
        }
        try {
            socket?.close()
        } catch (e: Exception) {}
        _isConnected.value = false
    }
}

class NativeSshShellChannel(private val session: NativeSshSession) : SshShellChannel {
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
            session.writeToShell(b, off, len)
        }
    }

    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        session.resizeShell(cols, rows)
    }

    override fun close() {
        // Session deinit handles this
    }
}

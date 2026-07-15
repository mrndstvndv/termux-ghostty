package com.mrndtvndv.term.data.ssh.jvm

import com.mrndtvndv.term.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import net.schmizz.sshj.xfer.FilePermission
import net.schmizz.sshj.xfer.FileSystemFile
import net.schmizz.sshj.xfer.TransferListener
import net.schmizz.sshj.common.StreamCopier
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class JvmSshSession : SshSession {
    private val client = SSHClient()
    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected
    private var config: SshConfig? = null

    override suspend fun connect(config: SshConfig) {
        this.config = config
        withContext(Dispatchers.IO) {
            client.addHostKeyVerifier(PromiscuousVerifier())
            client.connectTimeout = config.connectionTimeoutMs
            client.connect(config.host, config.port)
        }
    }

    override suspend fun authenticate(auth: SshAuth) {
        val currentConfig = config ?: throw IllegalStateException("Session not connected")
        withContext(Dispatchers.IO) {
            when (auth) {
                is SshAuth.Password -> {
                    client.authPassword(currentConfig.username, String(auth.password))
                }
                is SshAuth.PublicKey -> {
                    val passwordFinder = auth.passphrase?.let { passphrase ->
                        object : PasswordFinder {
                            override fun reqPassword(resource: Resource<*>?): CharArray {
                                return passphrase
                            }
                            override fun shouldRetry(resource: Resource<*>?): Boolean {
                                return false
                            }
                        }
                    }
                    val keyProvider = client.loadKeys(auth.privateKeyPem, null, passwordFinder)
                    client.authPublickey(currentConfig.username, keyProvider)
                }
            }
            _isConnected.value = client.isAuthenticated
        }
    }

    override suspend fun openShellChannel(termType: String, cols: Int, rows: Int): SshShellChannel {
        return withContext(Dispatchers.IO) {
            val session = client.startSession()
            session.allocatePTY(termType, cols, rows, 0, 0, emptyMap())
            val shell = session.startShell()
            JvmSshShellChannel(session, shell)
        }
    }

    override suspend fun openSftpClient(): SftpClient {
        return withContext(Dispatchers.IO) {
            val sftp = client.newSFTPClient()
            JvmSftpClient(sftp)
        }
    }

    override fun disconnect() {
        try {
            client.disconnect()
        } catch (e: Exception) {
            // ignore
        } finally {
            _isConnected.value = false
        }
    }
}

class JvmSshShellChannel(
    private val session: Session,
    private val shell: Session.Shell
) : SshShellChannel {
    override val inputStream: InputStream
        get() = shell.inputStream
    override val outputStream: OutputStream
        get() = shell.outputStream

    override fun resizeWindow(cols: Int, rows: Int, widthPx: Int, heightPx: Int) {
        try {
            shell.changeWindowDimensions(cols, rows, widthPx, heightPx)
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun close() {
        try {
            shell.close()
        } catch (e: Exception) {
            // ignore
        }
        try {
            session.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}

class JvmSftpClient(private val sftp: SFTPClient) : SftpClient {

    override suspend fun listFiles(path: String): List<SftpFile> = withContext(Dispatchers.IO) {
        val list = sftp.ls(path)
        list.filter { it.name != "." && it.name != ".." }.map { info ->
            val isDir = info.isDirectory()
            val size = if (isDir) 0L else info.attributes.size
            val permissions = FilePermission.toMask(info.attributes.permissions)
            val modifiedTime = info.attributes.mtime * 1000L
            SftpFile(
                name = info.name,
                path = info.path,
                isDirectory = isDir,
                size = size,
                permissions = permissions,
                modifiedTime = modifiedTime
            )
        }
    }

    override suspend fun createDirectory(path: String): Unit = withContext(Dispatchers.IO) {
        sftp.mkdir(path)
    }

    override suspend fun deleteFile(path: String): Unit = withContext(Dispatchers.IO) {
        val stat = sftp.stat(path)
        if (stat.type == FileMode.Type.DIRECTORY) {
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    override suspend fun downloadFile(
        remotePath: String,
        destination: File,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val transfer = sftp.fileTransfer
        val originalListener = transfer.transferListener
        try {
            transfer.transferListener = object : TransferListener {
                override fun directory(name: String?): TransferListener {
                    return this
                }

                override fun file(name: String?, size: Long): StreamCopier.Listener {
                    return StreamCopier.Listener { transferred ->
                        onProgress(transferred)
                    }
                }
            }
            sftp.get(remotePath, FileSystemFile(destination))
        } finally {
            transfer.transferListener = originalListener
        }
    }

    override suspend fun uploadFile(
        source: File,
        remotePath: String,
        onProgress: (Long) -> Unit
    ): Unit = withContext(Dispatchers.IO) {
        val transfer = sftp.fileTransfer
        val originalListener = transfer.transferListener
        try {
            transfer.transferListener = object : TransferListener {
                override fun directory(name: String?): TransferListener {
                    return this
                }

                override fun file(name: String?, size: Long): StreamCopier.Listener {
                    return StreamCopier.Listener { transferred ->
                        onProgress(transferred)
                    }
                }
            }
            sftp.put(FileSystemFile(source), remotePath)
        } finally {
            transfer.transferListener = originalListener
        }
    }

    override fun close() {
        try {
            sftp.close()
        } catch (e: Exception) {
            // ignore
        }
    }
}

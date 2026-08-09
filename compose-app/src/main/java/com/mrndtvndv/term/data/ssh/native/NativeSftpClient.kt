package com.mrndtvndv.term.data.ssh.native

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.termux.terminal.GhosttyNative
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class NativeSftpClient(
    private var sftpHandle: Long,
    private var sessionHandle: Long
) : SftpClient {

    private val lock = Any()

    override suspend fun listFiles(path: String): List<SftpFile> = withContext(Dispatchers.IO) {
        val jsonStr = synchronized(lock) {
            if (sessionHandle == 0L || sftpHandle == 0L) throw IOException("SFTP client is closed")
            GhosttyNative.nativeSftpListFiles(sessionHandle, sftpHandle, path)
        } ?: throw IOException("Failed to list files or directory does not exist")
        
        val jsonArray = org.json.JSONArray(jsonStr)
        val files = mutableListOf<SftpFile>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            files.add(
                SftpFile(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    isDirectory = obj.getBoolean("isDir"),
                    size = obj.getLong("size"),
                    permissions = obj.getInt("permissions"),
                    modifiedTime = obj.getLong("mtime")
                )
            )
        }
        files
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        val success = synchronized(lock) {
            if (sessionHandle == 0L || sftpHandle == 0L) throw IOException("SFTP client is closed")
            GhosttyNative.nativeSftpMkdir(sessionHandle, sftpHandle, path, 493) // 493 is octal 0755
        }
        if (!success) throw IOException("Failed to create directory")
    }

    override suspend fun deleteFile(path: String) = withContext(Dispatchers.IO) {
        val success = synchronized(lock) {
            if (sessionHandle == 0L || sftpHandle == 0L) throw IOException("SFTP client is closed")
            GhosttyNative.nativeSftpDelete(sessionHandle, sftpHandle, path)
        }
        if (!success) throw IOException("Failed to delete $path")
    }

    override suspend fun downloadFile(
        remotePath: String,
        destination: File,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileHandle = synchronized(lock) {
            if (sessionHandle == 0L || sftpHandle == 0L) throw IOException("SFTP client is closed")
            GhosttyNative.nativeSftpFileOpen(sessionHandle, sftpHandle, remotePath, 0x00000001, 0) // READ
        }
        if (fileHandle == 0L) throw IOException("Failed to open remote file: $remotePath")
        try {
            destination.parentFile?.mkdirs()
            destination.outputStream().use { localOut ->
                val buffer = ByteArray(256 * 1024)
                var totalRead = 0L
                while (true) {
                    val read = synchronized(lock) {
                        if (sessionHandle == 0L) throw IOException("SFTP client is closed")
                        GhosttyNative.nativeSftpFileRead(sessionHandle, fileHandle, buffer, 0, buffer.size)
                    }
                    if (read < 0) throw IOException("Error reading remote file: $remotePath")
                    if (read == 0) break
                    localOut.write(buffer, 0, read)
                    totalRead += read
                    onProgress(totalRead)
                }
            }
        } finally {
            synchronized(lock) {
                if (sessionHandle != 0L) {
                    GhosttyNative.nativeSftpFileClose(sessionHandle, fileHandle)
                }
            }
        }
    }

    override suspend fun uploadFile(
        source: File,
        remotePath: String,
        onProgress: (Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        val fileHandle = synchronized(lock) {
            if (sessionHandle == 0L || sftpHandle == 0L) throw IOException("SFTP client is closed")
            GhosttyNative.nativeSftpFileOpen(
                sessionHandle,
                sftpHandle,
                remotePath,
                0x00000002 or 0x00000008 or 0x00000010, // WRITE | CREAT | TRUNC
                420 // 420 is octal 0644
            )
        }
        if (fileHandle == 0L) throw IOException("Failed to open remote file: $remotePath")
        try {
            source.inputStream().use { localIn ->
                val buffer = ByteArray(256 * 1024)
                var totalWritten = 0L
                while (true) {
                    val read = localIn.read(buffer)
                    if (read == -1) break
                    var offset = 0
                    var remaining = read
                    while (remaining > 0) {
                        val written = synchronized(lock) {
                            if (sessionHandle == 0L) throw IOException("SFTP client is closed")
                            GhosttyNative.nativeSftpFileWrite(sessionHandle, fileHandle, buffer, offset, remaining)
                        }
                        if (written < 0) throw IOException("Error writing remote file: $remotePath")
                        offset += written
                        remaining -= written
                        totalWritten += written
                        onProgress(totalWritten)
                    }
                }
            }
        } finally {
            synchronized(lock) {
                if (sessionHandle != 0L) {
                    GhosttyNative.nativeSftpFileClose(sessionHandle, fileHandle)
                }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            val sess = sessionHandle
            val sftp = sftpHandle
            if (sess != 0L && sftp != 0L) {
                GhosttyNative.nativeSftpClose(sess, sftp)
                sessionHandle = 0L
                sftpHandle = 0L
            }
        }
    }
}

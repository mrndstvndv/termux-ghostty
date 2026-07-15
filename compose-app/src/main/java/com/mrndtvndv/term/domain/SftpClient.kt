package com.mrndtvndv.term.domain

import java.io.File

data class SftpFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val permissions: Int,
    val modifiedTime: Long
)

interface SftpClient {
    suspend fun listFiles(path: String): List<SftpFile>
    suspend fun createDirectory(path: String)
    suspend fun deleteFile(path: String)
    suspend fun downloadFile(remotePath: String, destination: File, onProgress: (Long) -> Unit)
    suspend fun uploadFile(source: File, remotePath: String, onProgress: (Long) -> Unit)
    fun close()
}

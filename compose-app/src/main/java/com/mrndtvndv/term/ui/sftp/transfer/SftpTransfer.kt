package com.mrndtvndv.term.ui.sftp.transfer

import java.io.File
import java.util.UUID

enum class TransferType {
    DOWNLOAD,
    UPLOAD
}

enum class TransferStatus {
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class SftpTransfer(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val remotePath: String,
    val localFile: File,
    val totalBytes: Long,
    val transferredBytes: Long = 0L,
    val type: TransferType,
    val status: TransferStatus = TransferStatus.RUNNING,
    val isMinimized: Boolean = false,
    val error: String? = null,
    val ownerKey: String? = null
) {
    val progress: Float
        get() = if (totalBytes > 0L) {
            (transferredBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isRunning: Boolean
        get() = status == TransferStatus.RUNNING

    val isCompleted: Boolean
        get() = status == TransferStatus.COMPLETED

    val isFailed: Boolean
        get() = status == TransferStatus.FAILED

    val isCancelled: Boolean
        get() = status == TransferStatus.CANCELLED
}

typealias SftpTransferType = TransferType
typealias SftpTransferStatus = TransferStatus

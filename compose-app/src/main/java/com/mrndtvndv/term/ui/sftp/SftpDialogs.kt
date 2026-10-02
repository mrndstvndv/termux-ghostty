package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransfer
import com.mrndtvndv.term.ui.sftp.transfer.TransferType

@Composable
fun SftpRenameDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onRename: (newName: String) -> Unit
) {
    var newName by remember(file.path) { mutableStateOf(file.name) }
    val isValid = newName.isNotBlank() && !newName.contains('/') && newName.trim() != file.name
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                singleLine = true,
                label = { Text("New name") },
                isError = newName.contains('/'),
                supportingText = {
                    if (newName.contains('/')) Text("Name cannot contain '/'")
                }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onRename(newName) },
                enabled = isValid
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SftpDeleteDialog(
    file: SftpFile,
    onDismiss: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${file.name}\"?") },
        text = {
            Text(
                if (file.isDirectory) {
                    "Empty directories can be deleted. This cannot be undone."
                } else {
                    "This file will be permanently deleted. This cannot be undone."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Suppress("LongParameterList")
@Composable
fun SftpTransferDialogs(
    transfers: List<SftpTransfer>,
    downloadState: SftpDownloadState?,
    uploadState: SftpUploadState?,
    onCancelTransfer: (SftpTransfer) -> Unit,
    onBackgroundTransfer: (SftpTransfer) -> Unit,
    onCancelDownload: () -> Unit,
    onCancelUpload: () -> Unit
) {
    if (transfers.isNotEmpty()) {
        val activeTransfer = transfers.firstOrNull { it.isRunning && !it.isMinimized }
        if (activeTransfer != null) {
            SftpTransferDialog(
                title = if (activeTransfer.type == TransferType.DOWNLOAD) "Downloading File" else "Uploading File",
                fileName = activeTransfer.fileName,
                bytesTransferred = activeTransfer.transferredBytes,
                totalBytes = activeTransfer.totalBytes,
                onCancel = { onCancelTransfer(activeTransfer) },
                onBackground = { onBackgroundTransfer(activeTransfer) }
            )
        }
    } else {
        // Without a transfer manager the view model reports progress through these states instead.
        downloadState?.let { state ->
            SftpTransferDialog(
                title = "Downloading File",
                fileName = state.fileName,
                bytesTransferred = state.bytesDownloaded,
                totalBytes = state.totalBytes,
                onCancel = onCancelDownload
            )
        }

        uploadState?.let { state ->
            SftpTransferDialog(
                title = "Uploading File",
                fileName = state.fileName,
                bytesTransferred = state.bytesUploaded,
                totalBytes = state.totalBytes,
                onCancel = onCancelUpload
            )
        }
    }
}

@Composable
private fun SftpTransferDialog(
    title: String,
    fileName: String,
    bytesTransferred: Long,
    totalBytes: Long,
    onCancel: () -> Unit,
    onBackground: (() -> Unit)? = null
) {
    AlertDialog(
        onDismissRequest = onCancel,
        confirmButton = {
            if (onBackground != null) {
                TextButton(onClick = onBackground) {
                    Text("Background")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val progress = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth()
                )

                val bytesText = if (totalBytes > 0) {
                    "${formatBytes(bytesTransferred)} / ${formatBytes(totalBytes)}"
                } else {
                    formatBytes(bytesTransferred)
                }
                Text(
                    text = bytesText,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    )
}

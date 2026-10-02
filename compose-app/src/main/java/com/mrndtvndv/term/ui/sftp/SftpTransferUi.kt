package com.mrndtvndv.term.ui.sftp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransfer

@Composable
fun SftpMinimizedTransferBanner(
    transfers: List<SftpTransfer>,
    onRestore: (SftpTransfer) -> Unit,
    onCancel: (SftpTransfer) -> Unit,
    modifier: Modifier = Modifier
) {
    val minimized = transfers.filter { it.isRunning && it.isMinimized }
    val latest = minimized.firstOrNull() ?: return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SftpBannerHeader(transfer = latest, count = minimized.size)
            LinearProgressIndicator(
                progress = { latest.progress },
                modifier = Modifier.fillMaxWidth()
            )
            SftpBannerActions(
                onRestore = { onRestore(latest) },
                onCancel = { onCancel(latest) }
            )
        }
    }
}

@Composable
private fun SftpBannerHeader(
    transfer: SftpTransfer,
    count: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val title = if (count > 1) {
            "${transfer.fileName} (+${count - 1})"
        } else {
            transfer.fileName
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        val bytesText = if (transfer.totalBytes > 0L) {
            "${formatBytes(transfer.transferredBytes)} / ${formatBytes(transfer.totalBytes)}"
        } else {
            formatBytes(transfer.transferredBytes)
        }
        Text(
            text = bytesText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

@Composable
private fun SftpBannerActions(
    onRestore: () -> Unit,
    onCancel: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onCancel,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Cancel")
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onRestore) {
            Text("Restore")
        }
    }
}

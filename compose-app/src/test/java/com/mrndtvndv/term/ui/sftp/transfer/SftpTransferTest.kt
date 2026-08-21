package com.mrndtvndv.term.ui.sftp.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SftpTransferTest {

    @Test
    fun testProgressCalculation() {
        val transfer = SftpTransfer(
            id = "test-1",
            fileName = "example.tar.gz",
            remotePath = "/tmp/example.tar.gz",
            localFile = File("/cache/example.tar.gz"),
            totalBytes = 1000L,
            transferredBytes = 500L,
            type = TransferType.DOWNLOAD
        )

        assertEquals(0.5f, transfer.progress, 0.001f)
        assertTrue(transfer.isRunning)
        assertFalse(transfer.isCompleted)
        assertFalse(transfer.isFailed)
        assertFalse(transfer.isCancelled)
    }

    @Test
    fun testZeroTotalBytesProgress() {
        val transfer = SftpTransfer(
            id = "test-2",
            fileName = "unknown_size.txt",
            remotePath = "/tmp/unknown_size.txt",
            localFile = File("/cache/unknown_size.txt"),
            totalBytes = 0L,
            transferredBytes = 500L,
            type = TransferType.DOWNLOAD
        )

        assertEquals(0f, transfer.progress, 0.001f)
    }

    @Test
    fun testStatusFlags() {
        val base = SftpTransfer(
            id = "test-3",
            fileName = "doc.pdf",
            remotePath = "/tmp/doc.pdf",
            localFile = File("/cache/doc.pdf"),
            totalBytes = 200L,
            type = TransferType.UPLOAD
        )

        val completed = base.copy(status = TransferStatus.COMPLETED)
        assertTrue(completed.isCompleted)
        assertFalse(completed.isRunning)

        val failed = base.copy(status = TransferStatus.FAILED, error = "Connection lost")
        assertTrue(failed.isFailed)
        assertEquals("Connection lost", failed.error)

        val cancelled = base.copy(status = TransferStatus.CANCELLED)
        assertTrue(cancelled.isCancelled)
    }

    @Test
    fun testFormatBytes() {
        assertEquals("0 B", SftpTransferNotificationHelper.formatBytes(0L))
        assertEquals("0 B", SftpTransferNotificationHelper.formatBytes(-5L))
        assertEquals("500 B", SftpTransferNotificationHelper.formatBytes(500L))
        assertEquals("1.00 KB", SftpTransferNotificationHelper.formatBytes(1024L))
        assertEquals("1.50 MB", SftpTransferNotificationHelper.formatBytes(1572864L))
    }

    @Test
    fun testOwnerKey() {
        val transferDefault = SftpTransfer(
            fileName = "doc.pdf",
            remotePath = "/tmp/doc.pdf",
            localFile = File("/cache/doc.pdf"),
            totalBytes = 200L,
            type = TransferType.UPLOAD
        )
        org.junit.Assert.assertNull(transferDefault.ownerKey)

        val transferWithOwner = transferDefault.copy(ownerKey = "server-123")
        assertEquals("server-123", transferWithOwner.ownerKey)
    }
}

package com.mrndtvndv.term.ui.sftp.transfer

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SftpTransferManagerTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Suppress("EmptyFunctionBlock")
    private class TestSftpClient : SftpClient {
        var onDownload: (suspend (remotePath: String, destination: File, onProgress: (Long) -> Unit) -> Unit)? = null
        var onUpload: (suspend (source: File, remotePath: String, onProgress: (Long) -> Unit) -> Unit)? = null

        override suspend fun listFiles(path: String): List<SftpFile> = emptyList()
        override suspend fun createDirectory(path: String) {}
        override suspend fun deleteFile(path: String) {}
        override suspend fun renameFile(oldPath: String, newPath: String) {}

        override suspend fun downloadFile(
            remotePath: String,
            destination: File,
            onProgress: (Long) -> Unit
        ) {
            onDownload?.invoke(remotePath, destination, onProgress)
        }

        override suspend fun uploadFile(
            source: File,
            remotePath: String,
            onProgress: (Long) -> Unit
        ) {
            onUpload?.invoke(source, remotePath, onProgress)
        }

        override fun close() {}
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createManager(scope: kotlinx.coroutines.CoroutineScope): SftpTransferManager {
        return SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = scope
        )
    }

    @Test
    fun testStartDownloadSuccess() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val client = TestSftpClient().apply {
            onDownload = { _, dest, onProgress ->
                onProgress(50L)
                onProgress(100L)
                dest.parentFile?.mkdirs()
                dest.writeText("downloaded")
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("sample.txt", "/remote/sample.txt", false, 100L, 0, 0)
        var fileReadyCalled = false
        var readyFile: File? = null

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir,
            onFileReady = { localFile ->
                fileReadyCalled = true
                readyFile = localFile
            }
        )

        val transfers = manager.transfers.value
        assertEquals(1, transfers.size)

        val transfer = transfers.first()
        assertEquals(id, transfer.id)
        assertEquals(TransferStatus.COMPLETED, transfer.status)
        assertEquals(100L, transfer.transferredBytes)
        assertEquals(1.0f, transfer.progress, 0.001f)
        assertTrue(fileReadyCalled)
        assertNotNull(readyFile)
    }

    @Test
    fun testStartDownloadFailure() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val client = TestSftpClient().apply {
            onDownload = { _, _, _ ->
                throw IOException("Network error")
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("error.txt", "/remote/error.txt", false, 50L, 0, 0)
        var errorCalled = false
        var errorMsg: String? = null

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir,
            onError = { msg ->
                errorCalled = true
                errorMsg = msg
            }
        )

        val transfer = manager.getTransfer(id)
        assertNotNull(transfer)
        assertEquals(TransferStatus.FAILED, transfer?.status)
        assertEquals("Network error", transfer?.error)
        assertTrue(errorCalled)
        assertEquals("Network error", errorMsg)
    }

    @Test
    fun testStartUploadSuccess() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val sourceFile = File.createTempFile("upload_src", ".txt").apply {
            writeText("upload test content")
        }
        val client = TestSftpClient().apply {
            onUpload = { _, _, onProgress ->
                onProgress(sourceFile.length())
            }
        }

        var successCalled = false
        val id = manager.startUpload(
            sftpClient = client,
            source = sourceFile,
            remotePath = "/remote/upload_src.txt",
            onSuccess = { successCalled = true }
        )

        val transfer = manager.getTransfer(id)
        assertNotNull(transfer)
        assertEquals(TransferStatus.COMPLETED, transfer?.status)
        assertEquals(TransferType.UPLOAD, transfer?.type)
        assertTrue(successCalled)

        sourceFile.delete()
    }

    @Test
    fun testCancelTransfer() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val cancelGate = CompletableDeferred<Unit>()
        val client = TestSftpClient().apply {
            onDownload = { _, _, onProgress ->
                onProgress(20L)
                cancelGate.await()
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("cancel_test.bin", "/remote/cancel.bin", false, 200L, 0, 0)

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir
        )

        val runningTransfer = manager.getTransfer(id)
        assertEquals(TransferStatus.RUNNING, runningTransfer?.status)

        manager.cancel(id)

        val cancelledTransfer = manager.getTransfer(id)
        assertNotNull(cancelledTransfer)
        assertEquals(TransferStatus.CANCELLED, cancelledTransfer?.status)
    }

    @Test
    fun testMinimizeRestoreAndDismiss() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val cancelGate = CompletableDeferred<Unit>()
        val client = TestSftpClient().apply {
            onDownload = { _, _, _ ->
                cancelGate.await()
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("min_test.txt", "/remote/min.txt", false, 100L, 0, 0)

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir
        )

        assertFalse(manager.getTransfer(id)!!.isMinimized)

        manager.minimize(id)
        assertTrue(manager.getTransfer(id)!!.isMinimized)

        manager.restore(id)
        assertFalse(manager.getTransfer(id)!!.isMinimized)

        manager.dismiss(id)
        assertNull(manager.getTransfer(id))
        assertTrue(manager.transfers.value.isEmpty())
    }

    @Test
    fun testActiveStateFlow() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val cancelGate = CompletableDeferred<Unit>()
        val client = TestSftpClient().apply {
            onDownload = { _, _, _ ->
                cancelGate.await()
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("active_test.txt", "/remote/active.txt", false, 100L, 0, 0)

        assertNull(manager.active.value)

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir
        )

        assertEquals(id, manager.active.value?.id)

        manager.cancel(id)

        assertNull(manager.active.value)
    }

    @Test
    fun testObserveTransferFlow() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val client = TestSftpClient().apply {
            onDownload = { _, dest, onProgress ->
                onProgress(50L)
                dest.parentFile?.mkdirs()
                dest.writeText("done")
            }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_cache")
        val file = SftpFile("flow_test.txt", "/remote/flow.txt", false, 50L, 0, 0)

        val id = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir
        )

        val flowTransfer = manager.observeTransfer(id).first { it?.status == TransferStatus.COMPLETED }
        assertNotNull(flowTransfer)
        assertEquals(TransferStatus.COMPLETED, flowTransfer?.status)
    }

    @Test
    fun testStartDownloadAndUploadWithOwnerKey() = runTest(testDispatcher) {
        val manager = createManager(backgroundScope)
        val cancelGate = CompletableDeferred<Unit>()
        val client = TestSftpClient().apply {
            onDownload = { _, _, _ -> cancelGate.await() }
            onUpload = { _, _, _ -> cancelGate.await() }
        }

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_test_owner_cache")
        val file = SftpFile("owner_dl.txt", "/remote/owner_dl.txt", false, 50L, 0, 0)
        val dlId = manager.startDownload(
            sftpClient = client,
            file = file,
            cacheDir = cacheDir,
            ownerKey = "server-alpha"
        )
        assertEquals("server-alpha", manager.getTransfer(dlId)?.ownerKey)

        val srcFile = File.createTempFile("owner_up", ".txt").apply { writeText("upload payload") }
        val upId = manager.startUpload(
            sftpClient = client,
            source = srcFile,
            remotePath = "/remote/owner_up.txt",
            ownerKey = "server-beta"
        )
        assertEquals("server-beta", manager.getTransfer(upId)?.ownerKey)

        srcFile.delete()
    }
}

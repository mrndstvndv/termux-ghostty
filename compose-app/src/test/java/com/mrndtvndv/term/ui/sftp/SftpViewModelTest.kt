package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.SavedStateHandle
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import com.mrndtvndv.term.ui.sftp.transfer.SftpTransferManager
import com.mrndtvndv.term.ui.sftp.transfer.TransferStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

@OptIn(ExperimentalCoroutinesApi::class)
class SftpViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    class MockSftpClient(private val filesMap: Map<String, List<SftpFile>> = emptyMap()) : SftpClient {
        var createDirCalledPath: String? = null
        var deleteCalledPath: String? = null
        var renamedOldPath: String? = null
        var renamedNewPath: String? = null
        var downloadedPath: String? = null
        var uploadedPath: String? = null
        var onDownloadHook: (suspend () -> Unit)? = null
        var onUploadHook: (suspend () -> Unit)? = null

        override suspend fun listFiles(path: String): List<SftpFile> {
            return filesMap[path] ?: throw Exception("Directory not found")
        }

        override suspend fun createDirectory(path: String) {
            createDirCalledPath = path
        }

        override suspend fun deleteFile(path: String) {
            deleteCalledPath = path
        }

        override suspend fun renameFile(oldPath: String, newPath: String) {
            renamedOldPath = oldPath
            renamedNewPath = newPath
        }

        override suspend fun downloadFile(
            remotePath: String,
            destination: File,
            onProgress: (Long) -> Unit
        ) {
            downloadedPath = remotePath
            onProgress(50L)
            onDownloadHook?.invoke()
            destination.parentFile?.mkdirs()
            destination.writeText("downloaded")
            onProgress(100L)
        }

        override suspend fun uploadFile(
            source: File,
            remotePath: String,
            onProgress: (Long) -> Unit
        ) {
            uploadedPath = remotePath
            onProgress(source.length() / 2)
            onUploadHook?.invoke()
            onProgress(source.length())
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

    @Test
    fun testNavigation() = runTest(testDispatcher) {
        val filesMap = mapOf(
            "/" to listOf(
                SftpFile("usr", "/usr", true, 0, 0, 0),
                SftpFile("file.txt", "/file.txt", false, 100, 0, 0)
            ),
            "/usr" to listOf(
                SftpFile("bin", "/usr/bin", true, 0, 0, 0)
            )
        )

        val client = MockSftpClient(filesMap)
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)

        advanceUntilIdle()

        val successState = viewModel.uiState.value as SftpUiState.Success
        assertEquals("/", successState.currentPath)
        assertEquals(2, successState.files.size)
        assertEquals("usr", successState.files[0].name)

        // Navigate to /usr
        viewModel.navigateTo("/usr")
        advanceUntilIdle()

        val successState2 = viewModel.uiState.value as SftpUiState.Success
        assertEquals("/usr", successState2.currentPath)
        assertEquals(1, successState2.files.size)
        assertEquals("bin", successState2.files[0].name)

        // Navigate up
        viewModel.navigateUp()
        advanceUntilIdle()
        assertEquals("/", viewModel.currentPath)
    }

    @Test
    fun testBreadcrumbTrailPreservedOnParentNavigation() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects/docs"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateTo("/home/projects")
        advanceUntilIdle()

        assertEquals("/home/projects/docs", viewModel.trailPath.value)
        assertEquals("/home/projects/docs", savedState.get<String>("trail_path"))
    }

    @Test
    fun testBreadcrumbTrailPreservedOnIntermediateChildNavigation() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects/docs/reports"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateTo("/home/projects")
        advanceUntilIdle()

        assertEquals("/home/projects", viewModel.currentPath)
        assertEquals("/home/projects/docs/reports", viewModel.trailPath.value)
    }

    @Test
    fun testBreadcrumbTrailPreservedOnNavigateUp() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateUp()
        advanceUntilIdle()

        assertEquals("/home", viewModel.currentPath)
        assertEquals("/home/projects", viewModel.trailPath.value)
    }

    @Test
    fun testBreadcrumbTrailPreservedOnRootNavigation() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateTo("/")
        advanceUntilIdle()

        assertEquals("/", viewModel.currentPath)
        assertEquals("/home/projects", viewModel.trailPath.value)
    }

    @Test
    fun testBreadcrumbTrailInvalidatedOnDivergentNavigation() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateTo("/var/log")
        advanceUntilIdle()

        assertEquals("/var/log", viewModel.trailPath.value)
        assertEquals("/var/log", savedState.get<String>("trail_path"))
    }

    @Test
    fun testBreadcrumbTrailExtendedOnDeeperNavigation() = runTest(testDispatcher) {
        val savedState = SavedStateHandle(mapOf("current_path" to "/home/projects"))
        val viewModel = SftpViewModel(MockSftpClient(), savedState, transferManager = null)

        advanceUntilIdle()
        viewModel.navigateTo("/home/projects/docs")
        advanceUntilIdle()

        assertEquals("/home/projects/docs", viewModel.trailPath.value)
        assertEquals("/home/projects/docs", savedState.get<String>("trail_path"))
    }

    @Test
    fun testDeleteFile() = runTest(testDispatcher) {
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)
        advanceUntilIdle()

        var succeeded = false
        viewModel.deleteFile(
            file = SftpFile("old.txt", "/data/old.txt", false, 0, 0, 0),
            onSuccess = { succeeded = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(succeeded)
        assertEquals("/data/old.txt", client.deleteCalledPath)
    }

    @Test
    fun testRenameFileBuildsSiblingPath() = runTest(testDispatcher) {
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)
        advanceUntilIdle()

        var succeeded = false
        viewModel.renameFile(
            file = SftpFile("old.txt", "/data/old.txt", false, 0, 0, 0),
            newName = " new-name.txt ",
            onSuccess = { succeeded = true },
            onError = {}
        )
        advanceUntilIdle()

        assertTrue(succeeded)
        assertEquals("/data/old.txt", client.renamedOldPath)
        assertEquals("/data/new-name.txt", client.renamedNewPath)
    }

    @Test
    fun testRenameFileRejectsInvalidName() = runTest(testDispatcher) {
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)
        advanceUntilIdle()

        var error: String? = null
        viewModel.renameFile(
            file = SftpFile("old.txt", "/old.txt", false, 0, 0, 0),
            newName = "sub/dir.txt",
            onSuccess = {},
            onError = { error = it }
        )
        advanceUntilIdle()

        assertNotNull(error)
        assertNull(client.renamedNewPath)

        // Same name is a no-op
        error = null
        viewModel.renameFile(
            file = SftpFile("old.txt", "/old.txt", false, 0, 0, 0),
            newName = "old.txt",
            onSuccess = {},
            onError = { error = it }
        )
        advanceUntilIdle()

        assertNull(error)
        assertNull(client.renamedNewPath)
    }

    @Test
    fun testDownloadFallbackAndCancel() = runTest(testDispatcher) {
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_vm_test")
        val file = SftpFile("test.txt", "/test.txt", false, 100L, 0, 0)

        var ready = false
        viewModel.downloadAndOpenFile(
            file = file,
            cacheDir = cacheDir,
            onFileReady = { ready = true },
            onError = {}
        )

        advanceUntilIdle()
        assertTrue(ready)
        assertNull(viewModel.downloadState.value)

        // Test cancel
        val gate = CompletableDeferred<Unit>()
        client.onDownloadHook = { gate.await() }

        viewModel.downloadAndOpenFile(
            file = file,
            cacheDir = cacheDir,
            onFileReady = {},
            onError = {}
        )
        testScheduler.runCurrent()
        assertNotNull(viewModel.downloadState.value)

        viewModel.cancelDownload()
        advanceUntilIdle()
        assertNull(viewModel.downloadState.value)
    }

    @Test
    fun testUploadFallbackAndCancel() = runTest(testDispatcher) {
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = null)

        val srcFile = File.createTempFile("vm_upload", ".txt").apply { writeText("data") }
        var uploaded = false

        viewModel.uploadFile(
            source = srcFile,
            onSuccess = { uploaded = true },
            onError = {}
        )

        advanceUntilIdle()
        assertTrue(uploaded)
        assertNull(viewModel.uploadState.value)

        // Test cancel
        val gate = CompletableDeferred<Unit>()
        client.onUploadHook = { gate.await() }

        viewModel.uploadFile(
            source = srcFile,
            onSuccess = {},
            onError = {}
        )
        testScheduler.runCurrent()
        assertNotNull(viewModel.uploadState.value)

        viewModel.cancelUpload()
        advanceUntilIdle()
        assertNull(viewModel.uploadState.value)

        srcFile.delete()
    }

    @Test
    fun testDownloadWithTransferManager() = runTest(testDispatcher) {
        val manager = SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = backgroundScope
        )
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = manager)

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_vm_mgr_test")
        val file = SftpFile("doc.pdf", "/remote/doc.pdf", false, 100L, 0, 0)
        var fileReady = false

        viewModel.downloadAndOpenFile(
            file = file,
            cacheDir = cacheDir,
            onFileReady = { fileReady = true },
            onError = {}
        )

        val transferId = viewModel.getActiveTransferId()
        assertNotNull(transferId)

        advanceUntilIdle()
        assertTrue(fileReady)
        assertNull(viewModel.downloadState.value)
    }

    @Test
    fun testUploadWithTransferManager() = runTest(testDispatcher) {
        val manager = SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = backgroundScope
        )
        val client = MockSftpClient()
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = manager)

        val srcFile = File.createTempFile("vm_mgr_up", ".txt").apply { writeText("upload payload") }
        var uploaded = false

        viewModel.uploadFile(
            source = srcFile,
            onSuccess = { uploaded = true },
            onError = {}
        )

        advanceUntilIdle()
        assertTrue(uploaded)
        assertNull(viewModel.uploadState.value)

        srcFile.delete()
    }

    @Test
    fun testTransferManagerHelpers() = runTest(testDispatcher) {
        val manager = SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = backgroundScope
        )
        val cancelGate = CompletableDeferred<Unit>()
        val client = MockSftpClient().apply {
            onDownloadHook = { cancelGate.await() }
        }
        val savedState = SavedStateHandle(mapOf("current_path" to "/"))
        val viewModel = SftpViewModel(client, savedState, transferManager = manager)

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_vm_helpers")
        val file = SftpFile("bg_test.bin", "/bg.bin", false, 200L, 0, 0)

        viewModel.downloadAndOpenFile(
            file = file,
            cacheDir = cacheDir,
            onFileReady = {},
            onError = {}
        )

        testScheduler.runCurrent()
        assertNotNull(viewModel.downloadState.value)
        assertEquals("bg_test.bin", viewModel.downloadState.value?.fileName)

        val id = viewModel.getActiveTransferId()
        assertNotNull(id)

        // Background download
        viewModel.backgroundDownload()
        testScheduler.runCurrent()
        assertNull(viewModel.downloadState.value)
        assertTrue(manager.getTransfer(id!!)!!.isMinimized)

        // Restore download
        viewModel.restoreDownload()
        testScheduler.runCurrent()
        assertNotNull(viewModel.downloadState.value)
        assertFalse(manager.getTransfer(id)!!.isMinimized)

        // Cancel download
        viewModel.cancelDownload()
        advanceUntilIdle()
        assertNull(viewModel.downloadState.value)
        assertEquals(TransferStatus.CANCELLED, manager.getTransfer(id)?.status)

        // Dismiss transfer
        viewModel.dismissTransfer(id)
        assertNull(manager.getTransfer(id))
    }

    @Test
    fun testDownloadIsolationBetweenServers() = runTest(testDispatcher) {
        val manager = SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = backgroundScope
        )
        val gateA = CompletableDeferred<Unit>()
        val clientA = MockSftpClient().apply { onDownloadHook = { gateA.await() } }
        val clientB = MockSftpClient()

        val vmA = SftpViewModel(
            client = clientA,
            savedStateHandle = SavedStateHandle(mapOf("current_path" to "/")),
            transferManager = manager,
            ownerKey = "server-a"
        )
        val vmB = SftpViewModel(
            client = clientB,
            savedStateHandle = SavedStateHandle(mapOf("current_path" to "/")),
            transferManager = manager,
            ownerKey = "server-b"
        )

        val cacheDir = File(System.getProperty("java.io.tmpdir"), "sftp_vm_iso_dl")
        val fileA = SftpFile("server_a_file.bin", "/server_a_file.bin", false, 1000L, 0, 0)

        vmA.downloadAndOpenFile(fileA, cacheDir, onFileReady = {}, onError = {})
        testScheduler.runCurrent()

        val idA = vmA.getActiveTransferId()
        assertNotNull(idA)
        assertEquals(1, vmA.transfers.value.size)
        assertEquals("server-a", vmA.transfers.value.first().ownerKey)
        assertNotNull(vmA.activeDownload.value)
        assertNotNull(vmA.downloadState.value)

        assertTrue(vmB.transfers.value.isEmpty())
        assertNull(vmB.activeDownload.value)
        assertNull(vmB.downloadState.value)
        assertNull(vmB.getActiveTransferId())

        gateA.complete(Unit)
    }

    @Test
    fun testUploadIsolationBetweenServers() = runTest(testDispatcher) {
        val manager = SftpTransferManager(
            context = null,
            notificationHelper = null,
            applicationScope = backgroundScope
        )
        val gateB = CompletableDeferred<Unit>()
        val clientA = MockSftpClient()
        val clientB = MockSftpClient().apply { onUploadHook = { gateB.await() } }

        val vmA = SftpViewModel(
            client = clientA,
            savedStateHandle = SavedStateHandle(mapOf("current_path" to "/")),
            transferManager = manager,
            ownerKey = "server-a"
        )
        val vmB = SftpViewModel(
            client = clientB,
            savedStateHandle = SavedStateHandle(mapOf("current_path" to "/")),
            transferManager = manager,
            ownerKey = "server-b"
        )

        val srcFileB = File.createTempFile("vm_b_iso_up", ".txt").apply { writeText("payload b") }
        vmB.uploadFile(srcFileB, onSuccess = {}, onError = {})
        testScheduler.runCurrent()

        val idB = vmB.getActiveTransferId()
        assertNotNull(idB)
        assertEquals(1, vmB.transfers.value.size)
        assertEquals("server-b", vmB.transfers.value.first().ownerKey)
        assertNotNull(vmB.activeUpload.value)
        assertNotNull(vmB.uploadState.value)

        assertTrue(vmA.transfers.value.isEmpty())
        assertNull(vmA.activeUpload.value)
        assertNull(vmA.uploadState.value)
        assertNull(vmA.getActiveTransferId())

        gateB.complete(Unit)
        srcFileB.delete()
    }
}

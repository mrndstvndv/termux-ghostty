package com.mrndtvndv.term.ui.sftp

import androidx.lifecycle.SavedStateHandle
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SftpViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    class MockSftpClient(private val filesMap: Map<String, List<SftpFile>>) : SftpClient {
        var createDirCalledPath: String? = null
        var deleteCalledPath: String? = null

        override suspend fun listFiles(path: String): List<SftpFile> {
            return filesMap[path] ?: throw Exception("Directory not found")
        }

        override suspend fun createDirectory(path: String) {
            createDirCalledPath = path
        }

        override suspend fun deleteFile(path: String) {
            deleteCalledPath = path
        }

        override suspend fun downloadFile(remotePath: String, destination: File, onProgress: (Long) -> Unit) {}
        override suspend fun uploadFile(source: File, remotePath: String, onProgress: (Long) -> Unit) {}
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
    fun testNavigation() = runTest {
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
        val viewModel = SftpViewModel(client, savedState)

        // Success state should be immediate with UnconfinedTestDispatcher
        val successState = viewModel.uiState.value as SftpUiState.Success
        assertEquals("/", successState.currentPath)
        assertEquals(2, successState.files.size)
        assertEquals("usr", successState.files[0].name)

        // Navigate to /usr
        viewModel.navigateTo("/usr")

        val successState2 = viewModel.uiState.value as SftpUiState.Success
        assertEquals("/usr", successState2.currentPath)
        assertEquals(1, successState2.files.size)
        assertEquals("bin", successState2.files[0].name)

        // Navigate up
        viewModel.navigateUp()
        assertEquals("/", viewModel.currentPath)
    }
}

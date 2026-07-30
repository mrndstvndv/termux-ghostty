package com.mrndtvndv.term.ui.review

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReviewViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun commitEscapesMessageAndRefreshesChanges() = runTest {
        val commands = mutableListOf<String>()
        var statusOutput = "M  app.kt\n"
        val workspaceDir = MutableStateFlow("/repo")
        val viewModel = ReviewViewModel(
            execCommand = { command ->
                commands += command
                when {
                    command.contains("git rev-parse") -> "/repo"
                    command.contains("git status") -> statusOutput
                    command.contains("git commit") -> {
                        statusOutput = ""
                        "[main abc123] Fix user's path\n__REVIEW_COMMIT_EXIT__0\n"
                    }
                    else -> ""
                }
            },
            workspaceDir = workspaceDir
        )

        advanceUntilIdle()
        assertEquals(1, (viewModel.uiState.value as ReviewUiState.Success).stagedFiles.size)

        viewModel.commit("Fix user's path")
        advanceUntilIdle()

        assertTrue(commands.any { it.contains("git commit -m 'Fix user'\"'\"'s path'") })
        assertEquals(
            ReviewUiState.Success(emptyList(), emptyList()),
            viewModel.uiState.value
        )
    }
}

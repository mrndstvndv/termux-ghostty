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
import org.junit.Assert.assertNotNull
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
                    command.contains("git rev-parse --show-toplevel") -> "/repo"
                    command.contains("git rev-parse --abbrev-ref") -> "main"
                    command.contains("git branch -a") -> "refs/heads/main|main|*\n"
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
        val successState = viewModel.uiState.value as ReviewUiState.Success
        assertTrue(successState.stagedFiles.isEmpty())
        assertEquals("main", successState.currentBranch)
    }

    @Test
    fun fetchesBranchInfoSuccessfully() = runTest {
        val workspaceDir = MutableStateFlow("/repo")
        val viewModel = ReviewViewModel(
            execCommand = { command ->
                when {
                    command.contains("git rev-parse --show-toplevel") -> "/repo"
                    command.contains("git rev-parse --abbrev-ref") -> "feature/test"
                    command.contains("git branch -a") ->
                        "refs/heads/main|main| \n" +
                        "refs/heads/feature/test|feature/test|*\n" +
                        "refs/remotes/origin/main|origin/main| \n" +
                        "refs/remotes/origin/HEAD|origin/HEAD| \n"
                    else -> ""
                }
            },
            workspaceDir = workspaceDir
        )

        advanceUntilIdle()

        val state = viewModel.uiState.value as ReviewUiState.Success
        assertEquals("feature/test", state.currentBranch)
        assertEquals(3, state.branches.size)
        assertEquals(GitBranch("main", isCurrent = false, isRemote = false), state.branches[0])
        assertEquals(GitBranch("feature/test", isCurrent = true, isRemote = false), state.branches[1])
        assertEquals(GitBranch("origin/main", isCurrent = false, isRemote = true), state.branches[2])
    }

    @Test
    fun checkoutBranchExecutesCommandAndRefreshes() = runTest {
        val commands = mutableListOf<String>()
        var currentBranchName = "main"
        val workspaceDir = MutableStateFlow("/repo")
        val viewModel = ReviewViewModel(
            execCommand = { command ->
                commands += command
                when {
                    command.contains("git rev-parse --show-toplevel") -> "/repo"
                    command.contains("git rev-parse --abbrev-ref") -> currentBranchName
                    command.contains("git branch -a") -> "refs/heads/$currentBranchName|$currentBranchName|*\n"
                    command.contains("git checkout 'feature/login'") -> {
                        currentBranchName = "feature/login"
                        "Switched to branch 'feature/login'\n__REVIEW_BRANCH_EXIT__0\n"
                    }
                    else -> ""
                }
            },
            workspaceDir = workspaceDir
        )

        advanceUntilIdle()
        assertEquals("main", (viewModel.uiState.value as ReviewUiState.Success).currentBranch)

        viewModel.checkoutBranch(GitBranch("feature/login", isCurrent = false))
        advanceUntilIdle()

        assertTrue(commands.any { it.contains("git checkout 'feature/login'") })
        assertEquals("feature/login", (viewModel.uiState.value as ReviewUiState.Success).currentBranch)
    }

    @Test
    fun createAndCheckoutBranchExecutesCommand() = runTest {
        val commands = mutableListOf<String>()
        var currentBranchName = "main"
        val workspaceDir = MutableStateFlow("/repo")
        val viewModel = ReviewViewModel(
            execCommand = { command ->
                commands += command
                when {
                    command.contains("git rev-parse --show-toplevel") -> "/repo"
                    command.contains("git rev-parse --abbrev-ref") -> currentBranchName
                    command.contains("git branch -a") -> "refs/heads/$currentBranchName|$currentBranchName|*\n"
                    command.contains("git checkout -b 'new-feature'") -> {
                        currentBranchName = "new-feature"
                        "Switched to a new branch 'new-feature'\n__REVIEW_BRANCH_EXIT__0\n"
                    }
                    else -> ""
                }
            },
            workspaceDir = workspaceDir
        )

        advanceUntilIdle()

        viewModel.createAndCheckoutBranch("new-feature")
        advanceUntilIdle()

        assertTrue(commands.any { it.contains("git checkout -b 'new-feature'") })
        assertEquals("new-feature", (viewModel.uiState.value as ReviewUiState.Success).currentBranch)
    }

    @Test
    fun checkoutBranchFailureShowsError() = runTest {
        val workspaceDir = MutableStateFlow("/repo")
        val viewModel = ReviewViewModel(
            execCommand = { command ->
                when {
                    command.contains("git rev-parse --show-toplevel") -> "/repo"
                    command.contains("git rev-parse --abbrev-ref") -> "main"
                    command.contains("git branch -a") -> "refs/heads/main|main|*\n"
                    command.contains("git checkout") ->
                        "error: Your local changes to the following files would be overwritten by checkout:\n" +
                        "\tapp.kt\n__REVIEW_BRANCH_EXIT__1\n"
                    else -> ""
                }
            },
            workspaceDir = workspaceDir
        )

        advanceUntilIdle()

        viewModel.checkoutBranch(GitBranch("feature/conflict", isCurrent = false))
        advanceUntilIdle()

        assertNotNull(viewModel.errorMessage.value)
        assertTrue(viewModel.errorMessage.value!!.contains("Failed to switch branch"))
        assertTrue(viewModel.errorMessage.value!!.contains("Your local changes"))
    }
}

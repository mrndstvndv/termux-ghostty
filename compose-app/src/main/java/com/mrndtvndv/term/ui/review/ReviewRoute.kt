package com.mrndtvndv.term.ui.review

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import com.mrndtvndv.term.navigation.ReviewRoute

@Suppress("LongMethod")
@Composable
fun ReviewScreenRoute(
    viewModel: ReviewViewModel,
    backStack: NavBackStack<NavKey>,
    onOpenDiff: (ReviewRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboardController = LocalSoftwareKeyboardController.current
    var dialog by remember { mutableStateOf<ReviewDialog?>(null) }
    val search = remember { DiffSearch() }
    val closeSearch = {
        keyboardController?.hide()
        search.reset()
    }

    dialog?.let {
        ReviewDialogHost(
            dialog = it,
            state = state,
            onDialogChange = { next -> dialog = next },
            onCommit = viewModel::commit,
            onRenameCommit = viewModel::renameCommit,
            onSoftReset = viewModel::softReset,
            onHardReset = viewModel::hardReset,
            onDiscard = viewModel::discardFileChanges,
            onCheckoutBranch = viewModel::checkoutBranch,
            onCreateBranch = viewModel::createAndCheckoutBranch
        )
    }
    state.errorMessage?.let { error ->
        ErrorDialog(message = error, onDismissRequest = viewModel::clearErrorMessage)
    }

    // Pop side effects follow the stack so every back path (gesture, toolbar, tab logic)
    // behaves the same. The shared onBack clears focus before popping: rescuing focus
    // from inside a disposed diff AndroidView races placement.
    val isOnChangesList = backStack.lastOrNull() is ReviewRoute.Changes
    LaunchedEffect(isOnChangesList) {
        if (isOnChangesList) {
            search.reset()
            viewModel.deselectFile()
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = onBack,
        entryProvider = entryProvider<NavKey> {
            entry<ReviewRoute.Changes> {
                FileChangesList(
                    state = state,
                    onToggleStagedExpanded = viewModel::toggleStagedExpanded,
                    onToggleUnstagedExpanded = viewModel::toggleUnstagedExpanded,
                    onToggleCommitsExpanded = viewModel::toggleCommitsExpanded,
                    onFileSelected = { file ->
                        closeSearch()
                        viewModel.selectFile(file)
                        onOpenDiff(ReviewRoute.FileDiff(file.path, file.isStaged))
                    },
                    onCommitSelected = { commit ->
                        closeSearch()
                        viewModel.selectCommit(commit)
                        onOpenDiff(ReviewRoute.CommitDiff(commit.hash))
                    },
                    onRenameCommitClick = { dialog = ReviewDialog.Rename(it) },
                    onSoftResetClick = { dialog = ReviewDialog.SoftReset(it) },
                    onHardResetClick = { dialog = ReviewDialog.HardReset(it) },
                    onLoadMoreCommits = viewModel::loadMoreCommits,
                    onStage = viewModel::stageFile,
                    onUnstage = viewModel::unstageFile,
                    onDiscard = { dialog = ReviewDialog.Discard(it) },
                    onStageBatch = viewModel::stageFiles,
                    onUnstageBatch = viewModel::unstageFiles,
                    onDiscardBatch = viewModel::discardFiles,
                    onCommit = { dialog = ReviewDialog.Commit },
                    onRefresh = viewModel::refresh,
                    onBranchHeaderClick = { dialog = ReviewDialog.Branches },
                    onFetch = viewModel::fetchRemote,
                    onPull = viewModel::pullBranch,
                    onPush = viewModel::pushBranch,
                    modifier = modifier
                )
            }
            entry<ReviewRoute.FileDiff> { key ->
                DiffPane(
                    title = state.selectedFile?.path ?: key.path,
                    subtitle = if (key.isStaged) "Staged Changes" else "Unstaged Changes",
                    state = state,
                    search = search,
                    showFullFileToggle = true,
                    onToggleFullFileMode = viewModel::toggleFullFileMode,
                    onToggleLineNumbers = viewModel::toggleLineNumbers,
                    onToggleWordDiff = viewModel::toggleWordDiff,
                    onCloseSearch = closeSearch,
                    onBack = onBack,
                    modifier = modifier
                )
            }
            entry<ReviewRoute.CommitDiff> { key ->
                DiffPane(
                    title = state.selectedCommit?.subject ?: "Commit Details",
                    subtitle = state.selectedCommit?.let { "Commit ${it.shortHash} • ${it.relativeDate}" }
                        ?: "Commit ${key.hash.take(7)}",
                    state = state,
                    search = search,
                    showFullFileToggle = false,
                    onToggleFullFileMode = viewModel::toggleFullFileMode,
                    onToggleLineNumbers = viewModel::toggleLineNumbers,
                    onToggleWordDiff = viewModel::toggleWordDiff,
                    onCloseSearch = closeSearch,
                    onBack = onBack,
                    modifier = modifier
                )
            }
        }
    )
}

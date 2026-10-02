@file:Suppress("MatchingDeclarationName")
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.ui.theme.codeFontFamily

internal sealed interface ReviewDialog {
    data object Commit : ReviewDialog
    data class Rename(val commit: GitCommit) : ReviewDialog
    data class SoftReset(val commit: GitCommit) : ReviewDialog
    data class HardReset(val commit: GitCommit) : ReviewDialog
    data class Discard(val file: GitFileStatus) : ReviewDialog
    data object Branches : ReviewDialog
    data object CreateBranch : ReviewDialog
}

@Suppress("LongParameterList", "LongMethod")
@Composable
internal fun ReviewDialogHost(
    dialog: ReviewDialog,
    state: ReviewScreenState,
    onDialogChange: (ReviewDialog?) -> Unit,
    onCommit: (String) -> Unit,
    onRenameCommit: (GitCommit, String) -> Unit,
    onSoftReset: (GitCommit) -> Unit,
    onHardReset: (GitCommit) -> Unit,
    onDiscard: (GitFileStatus) -> Unit,
    onCheckoutBranch: (GitBranch) -> Unit,
    onCreateBranch: (String) -> Unit
) {
    val dismiss = { onDialogChange(null) }
    val content = state.content as? ReviewUiState.Success
    when (dialog) {
        ReviewDialog.Commit -> CommitDialog(
            stagedFileCount = content?.stagedFiles?.size ?: 0,
            isBusy = state.isCommitInProgress,
            onConfirm = { message ->
                onCommit(message)
                dismiss()
            },
            onDismissRequest = dismiss
        )
        is ReviewDialog.Rename -> RenameCommitDialog(
            commit = dialog.commit,
            isBusy = state.isCommitInProgress,
            onConfirm = { subject ->
                onRenameCommit(dialog.commit, subject)
                dismiss()
            },
            onDismissRequest = dismiss
        )
        is ReviewDialog.SoftReset -> ResetDialog(
            title = "Soft Reset to Commit",
            commit = dialog.commit,
            description = "Commits after ${dialog.commit.shortHash} will be undone and their changes " +
                "moved to the staged area. Working tree changes are kept.",
            confirmLabel = "Reset",
            isDestructive = false,
            isBusy = state.isCommitInProgress,
            onConfirm = {
                dismiss()
                onSoftReset(dialog.commit)
            },
            onDismissRequest = dismiss
        )
        is ReviewDialog.HardReset -> ResetDialog(
            title = "Hard Reset to Commit",
            commit = dialog.commit,
            description = "All commits after ${dialog.commit.shortHash} AND all uncommitted changes " +
                "will be permanently discarded. This cannot be undone.",
            confirmLabel = "Delete & Reset",
            isDestructive = true,
            isBusy = state.isCommitInProgress,
            onConfirm = {
                dismiss()
                onHardReset(dialog.commit)
            },
            onDismissRequest = dismiss
        )
        is ReviewDialog.Discard -> DiscardDialog(
            file = dialog.file,
            onConfirm = {
                onDiscard(dialog.file)
                dismiss()
            },
            onDismissRequest = dismiss
        )
        ReviewDialog.Branches -> BranchSelectorDialog(
            currentBranch = content?.currentBranch.orEmpty(),
            branches = content?.branches.orEmpty(),
            isOperationInProgress = state.isBranchOperationInProgress,
            onBranchSelected = { branch ->
                onCheckoutBranch(branch)
                dismiss()
            },
            onCreateNewBranchClick = { onDialogChange(ReviewDialog.CreateBranch) },
            onDismissRequest = dismiss
        )
        ReviewDialog.CreateBranch -> CreateBranchDialog(
            isOperationInProgress = state.isBranchOperationInProgress,
            onConfirm = { name ->
                onCreateBranch(name)
                dismiss()
            },
            onDismissRequest = dismiss
        )
    }
}

@Composable
internal fun ErrorDialog(message: String, onDismissRequest: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Git Error") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("OK")
            }
        }
    )
}

@Composable
internal fun BatchDiscardDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Discard $count Changes?") },
        text = {
            Text(
                "Are you sure you want to discard all local changes to the $count " +
                    "selected file(s)? This cannot be undone."
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Discard All")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun DiscardDialog(
    file: GitFileStatus,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Discard Changes?") },
        text = {
            Text(
                if (file.status == "??") {
                    "Are you sure you want to permanently delete untracked file '${file.path}'?"
                } else {
                    "Are you sure you want to discard all local changes to '${file.path}'? This cannot be undone."
                }
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Discard")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CommitDialog(
    stagedFileCount: Int,
    isBusy: Boolean,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var message by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        title = { Text("Commit Changes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Commit $stagedFileCount staged file${if (stagedFileCount == 1) "" else "s"}.")
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Commit message") },
                    placeholder = { Text("Describe your changes") },
                    minLines = 2,
                    maxLines = 5,
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(message) },
                enabled = message.isNotBlank() && !isBusy
            ) {
                ButtonLabel("Commit", isBusy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isBusy) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun RenameCommitDialog(
    commit: GitCommit,
    isBusy: Boolean,
    onConfirm: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    var subject by remember { mutableStateOf(commit.subject) }
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        title = { Text("Edit Commit Message") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommitHashLabel("Commit ${commit.shortHash}")
                OutlinedTextField(
                    value = subject,
                    onValueChange = { subject = it },
                    label = { Text("Commit Message") },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(subject.trim()) },
                enabled = subject.isNotBlank() && !isBusy
            ) {
                ButtonLabel("Save", isBusy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isBusy) {
                Text("Cancel")
            }
        }
    )
}

@Suppress("LongParameterList")
@Composable
private fun ResetDialog(
    title: String,
    commit: GitCommit,
    description: String,
    confirmLabel: String,
    isDestructive: Boolean,
    isBusy: Boolean,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismissRequest() },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CommitHashLabel("Reset to ${commit.shortHash}?")
                Text(description)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isBusy,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else {
                    ButtonDefaults.buttonColors()
                }
            ) {
                ButtonLabel(confirmLabel, isBusy)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest, enabled = !isBusy) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CommitHashLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = codeFontFamily()),
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    )
}

@Composable
private fun ButtonLabel(label: String, isBusy: Boolean) {
    if (isBusy) {
        LoadingIndicator(modifier = Modifier.size(18.dp))
    } else {
        Text(label)
    }
}

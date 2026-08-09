package com.termux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.internal.TerminalController
import com.termux.terminal.compose.internal.TerminalSelectionActionMode

/**
 * Keeps the floating action toolbar in sync with selection state.
 *
 * For an empty selection the update is a no-op, so the empty branch keys only
 * on the action-mode instance instead of the per-frame keys (contentVersion,
 * currentFrame). That prevents canceling and relaunching this effect once per
 * published frame during output bursts. The non-empty branch observes the
 * publish counter from inside a coroutine (never from composition), so it
 * refreshes per frame without forcing recomposition of the canvas.
 */
@Suppress("LongParameterList")
@Composable
internal fun TerminalSelectionActionUpdater(
    contentVersionState: MutableIntState,
    selection: TerminalSelection,
    controller: TerminalController,
    metrics: TerminalMetrics,
    canvasPositionInWindow: Offset,
    config: TerminalCanvasConfig,
    selectionActionMode: TerminalSelectionActionMode,
    clearSelection: () -> Unit
) {
    if (selection.isEmpty) {
        LaunchedEffect(selectionActionMode) {
            selectionActionMode.update(
                selection = TerminalSelection.EMPTY,
                frame = null,
                selectedTextProvider = { "" },
                metrics = metrics,
                canvasPositionInWindow = canvasPositionInWindow,
                config = config,
                clearSelection = clearSelection
            )
        }
    } else {
        LaunchedEffect(
            selection,
            metrics,
            canvasPositionInWindow,
            config.onCopyRequest,
            config.onPasteRequest,
            config.onMoreSelectionRequest
        ) {
            snapshotFlow { contentVersionState.intValue }.collect {
                selectionActionMode.update(
                    selection = selection,
                    frame = controller.currentFrame(),
                    selectedTextProvider = { controller.selectedText(selection) },
                    metrics = metrics,
                    canvasPositionInWindow = canvasPositionInWindow,
                    config = config,
                    clearSelection = clearSelection
                )
            }
        }
    }
}

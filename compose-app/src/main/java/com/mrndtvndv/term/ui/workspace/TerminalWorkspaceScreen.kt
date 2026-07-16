package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.termux.terminal.TerminalSession
import com.mrndtvndv.term.ui.keyboard.ExtraKeysToolbar
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController
import com.termux.view.TerminalView

class Ref<T>(var value: T? = null)

@Composable
fun TerminalWorkspaceScreen(
    session: TerminalSession,
    extraKeysEnabled: Boolean,
    extraKeysJson: String,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    modifier: Modifier = Modifier
) {
    val extraKeysController = remember { ExtraKeysController() }
    val activeTerminalViewRef = remember { Ref<TerminalView>() }
    val getActiveTerminalView = remember { { activeTerminalViewRef.value } }

    val handleViewCreated: (TerminalView) -> Unit = { view ->
        activeTerminalViewRef.value = view
        onViewCreated(view)
    }

    val handleViewReleased: (TerminalView) -> Unit = { view ->
        if (activeTerminalViewRef.value === view) {
            activeTerminalViewRef.value = null
        }
        onViewReleased(view)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            TerminalFocusWrapper(
                session = session,
                extraKeysController = extraKeysController,
                isTerminalActive = true,
                onViewCreated = handleViewCreated,
                onViewReleased = handleViewReleased
            )
        }
        if (extraKeysEnabled) {
            ExtraKeysToolbar(
                extraKeysController = extraKeysController,
                getActiveTerminalView = getActiveTerminalView,
                session = session,
                extraKeysJson = extraKeysJson
            )
        }
    }
}

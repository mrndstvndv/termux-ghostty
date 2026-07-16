package com.mrndtvndv.term.ui.workspace

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.termux.terminal.TerminalSession
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import com.mrndtvndv.term.ui.keyboard.ExtraKeysController

fun TerminalSession.handleKeyEvent(keyEvent: KeyEvent, extraKeysController: ExtraKeysController): Boolean {
    val nativeEvent = keyEvent.nativeKeyEvent
    val keyCode = nativeEvent.keyCode
    val type = keyEvent.type

    // Only process Arrow keys, ESC, and Tab
    val isArrowKey = keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP ||
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT ||
            keyCode == AndroidKeyEvent.KEYCODE_DPAD_RIGHT
    val isEsc = keyCode == AndroidKeyEvent.KEYCODE_ESCAPE
    val isTab = keyCode == AndroidKeyEvent.KEYCODE_TAB

    if (!isArrowKey && !isEsc && !isTab) {
        return false
    }

    if (type == KeyEventType.KeyDown) {
        var keyMod = 0
        if (nativeEvent.isShiftPressed || extraKeysController.readShift()) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (nativeEvent.isCtrlPressed || extraKeysController.readControl()) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (nativeEvent.isAltPressed || extraKeysController.readAlt()) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (nativeEvent.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

        val code = KeyHandler.getCode(
            keyCode,
            keyMod,
            this.isCursorKeysApplicationMode,
            this.isKeypadApplicationMode
        )
        if (code != null) {
            this.write(code)
            return true
        }
    } else if (type == KeyEventType.KeyUp) {
        return true
    }
    return false
}

@Composable
fun TerminalFocusWrapper(
    session: TerminalSession,
    extraKeysController: ExtraKeysController,
    isTerminalActive: Boolean,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: (TerminalView) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(isTerminalActive) {
        if (isTerminalActive) {
            focusRequester.requestFocus()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { keyEvent ->
                session.handleKeyEvent(keyEvent, extraKeysController)
            }
    ) {
        TerminalWorkspaceContainer(
            session = session,
            extraKeysController = extraKeysController,
            onViewCreated = onViewCreated,
            onViewReleased = onViewReleased
        )
    }
}

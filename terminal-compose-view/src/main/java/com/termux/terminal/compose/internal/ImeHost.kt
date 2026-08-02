@file:Suppress("DEPRECATION") // LocalTextInputService/TextInputSession remain the supported IME bridge

package com.termux.terminal.compose.internal

import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TextInputService
import androidx.compose.ui.text.input.TextInputSession
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import kotlin.math.abs

/**
 * Creates the IME host for the current composition, reading the platform
 * text-input service. Deprecated platform API usage stays inside this file.
 */
@Composable
internal fun rememberImeHost(
    onEditCommands: (List<EditCommand>) -> Unit
): ImeHost {
    val textInputService = LocalTextInputService.current
    return remember(textInputService, onEditCommands) { ImeHost(textInputService, onEditCommands) }
}

/**
 * Hosts the soft-keyboard text-input session for the canvas.
 *
 * All deprecated platform text-input compatibility lives in this file: the
 * session starts with an empty invisible buffer, edit commands are translated
 * in order by [ImeEditCommandProcessor], and the session is closed on detach,
 * focus loss, and disposal.
 */
internal class ImeHost(
    private val textInputService: TextInputService?,
    private val onEditCommands: (List<EditCommand>) -> Unit
) {
    private var session: TextInputSession? = null

    val isOpen: Boolean
        get() = session?.isOpen == true

    /** Opens a fresh session (restarting any existing one) and shows the keyboard. */
    fun open() {
        session?.dispose()
        session = textInputService?.startInput(
            value = TextFieldValue(""),
            imeOptions = ImeOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
                autoCorrect = false
            ),
            onEditCommand = onEditCommands,
            onImeActionPerformed = { }
        )
        session?.showSoftwareKeyboard()
    }

    /** Shows the keyboard for the existing session, if any. */
    fun showKeyboard() {
        session?.showSoftwareKeyboard()
    }

    /** Closes the session; idempotent. */
    fun close() {
        session?.dispose()
        session = null
    }
}

/**
 * Adapter from the IME state machine's semantic terminal ops to the backend
 * contract. Ctrl/alt sticky modifiers are honored through
 * [TerminalInputTranslator].
 */
internal class CommandTerminalInput(
    private val translator: TerminalInputTranslator
) : ImeEditCommandProcessor.TerminalInput {

    override fun inputCodePoint(codePoint: Int) {
        translator.sendCodePoint(if (codePoint == '\n'.code) 13 else codePoint, alt = false)
    }

    override fun delete() {
        val result = translator.submit(
            TerminalCommand.Key(keyCode = KeyEvent.KEYCODE_DEL, metaState = 0, down = true)
        )
        if (result !is TerminalCommandResult.Success) {
            translator.sendCodePoint(127, alt = false)
        }
    }

    override fun moveCursor(delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            translator.submit(TerminalCommand.Key(keyCode = keyCode, metaState = 0, down = true))
        }
    }
}

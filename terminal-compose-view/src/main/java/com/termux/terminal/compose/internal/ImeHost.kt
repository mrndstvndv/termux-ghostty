package com.termux.terminal.compose.internal

import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
internal fun rememberImeHost(
    onEditCommands: (List<EditCommand>) -> Unit,
    onSessionStarted: () -> Unit = {},
    onSessionClosed: () -> Unit = {}
): ImeHost {
    val currentOnEditCommands = rememberUpdatedState(onEditCommands)
    val currentOnSessionStarted = rememberUpdatedState(onSessionStarted)
    val currentOnSessionClosed = rememberUpdatedState(onSessionClosed)
    return remember {
        ImeHost(
            onEditCommands = { currentOnEditCommands.value(it) },
            onSessionStarted = { currentOnSessionStarted.value() },
            onSessionClosed = { currentOnSessionClosed.value() }
        )
    }
}

/** Owns the terminal's platform text-input session. */
internal class ImeHost(
    internal val onEditCommands: (List<EditCommand>) -> Unit,
    internal val onSessionStarted: () -> Unit,
    internal val onSessionClosed: () -> Unit
) {
    private var node: ImeHostNode? = null

    fun open() {
        node?.open()
    }

    fun close() {
        node?.close()
    }

    internal fun attach(node: ImeHostNode) {
        this.node = node
    }

    internal fun detach(node: ImeHostNode) {
        if (this.node === node) this.node = null
    }
}

internal fun Modifier.terminalImeHost(imeHost: ImeHost): Modifier = then(ImeHostElement(imeHost))

private data class ImeHostElement(
    val imeHost: ImeHost
) : ModifierNodeElement<ImeHostNode>() {
    override fun create(): ImeHostNode = ImeHostNode(imeHost)

    override fun update(node: ImeHostNode) {
        node.updateHost(imeHost)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "terminalImeHost"
    }
}

internal class ImeHostNode(
    private var imeHost: ImeHost
) : Modifier.Node(), PlatformTextInputModifierNode {
    private var sessionJob: Job? = null

    override fun onAttach() {
        imeHost.attach(this)
    }

    override fun onDetach() {
        close()
        imeHost.detach(this)
    }

    fun updateHost(nextImeHost: ImeHost) {
        if (imeHost === nextImeHost) return
        close()
        if (isAttached) imeHost.detach(this)
        imeHost = nextImeHost
        if (isAttached) imeHost.attach(this)
    }

    fun open() {
        sessionJob?.cancel()
        imeHost.onSessionStarted()
        sessionJob = coroutineScope.launch {
            establishTextInputSession {
                val hostView = view
                startInputMethod(
                    PlatformTextInputMethodRequest { editorInfo ->
                        editorInfo.configureForTerminal()
                        TerminalInputConnection(hostView, imeHost.onEditCommands)
                    }
                )
            }
        }
    }

    fun close() {
        val wasOpen = sessionJob != null
        sessionJob?.cancel()
        sessionJob = null
        if (wasOpen) imeHost.onSessionClosed()
    }
}

/** Input connection with the same empty, invisible buffer contract as the legacy Compose API. */
internal class TerminalInputConnection(
    view: View,
    private val onEditCommands: (List<EditCommand>) -> Unit
) : BaseInputConnection(view, true) {
    private var pendingCommands = arrayListOf<EditCommand>()
    private var dispatchCommands = arrayListOf<EditCommand>()
    private var batchDepth = 0
    private var active = true

    override fun beginBatchEdit(): Boolean {
        if (!active) return false
        batchDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (!active) return false
        if (batchDepth > 0) batchDepth--
        flushCommands()
        return batchDepth > 0
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        enqueue(CommitTextCommand(text.toString(), newCursorPosition))

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        enqueue(SetComposingRegionCommand(start, end))

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        enqueue(SetComposingTextCommand(text.toString(), newCursorPosition))

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        enqueue(DeleteSurroundingTextCommand(beforeLength, afterLength))

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        enqueue(DeleteSurroundingTextInCodePointsCommand(beforeLength, afterLength))

    override fun setSelection(start: Int, end: Int): Boolean = enqueue(SetSelectionCommand(start, end))

    override fun finishComposingText(): Boolean = enqueue(FinishComposingTextCommand())

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (!active) return false
        if (event.action != KeyEvent.ACTION_DOWN) return true
        return enqueueKeyEvent(event)
    }

    override fun closeConnection() {
        active = false
        pendingCommands.clear()
        dispatchCommands.clear()
        batchDepth = 0
        super.closeConnection()
    }

    private fun enqueueKeyEvent(event: KeyEvent): Boolean {
        val command = event.toEditCommand() ?: return super.sendKeyEvent(event)
        return enqueue(command)
    }

    private fun enqueue(command: EditCommand): Boolean {
        if (!active) return false
        pendingCommands += command
        flushCommands()
        return true
    }

    private fun flushCommands() {
        if (batchDepth > 0 || pendingCommands.isEmpty()) return
        val commands = pendingCommands
        pendingCommands = dispatchCommands
        dispatchCommands = commands
        try {
            onEditCommands(commands)
        } finally {
            commands.clear()
        }
    }
}

private fun EditorInfo.configureForTerminal() {
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    imeOptions = EditorInfo.IME_ACTION_NONE or
        EditorInfo.IME_FLAG_FORCE_ASCII or
        EditorInfo.IME_FLAG_NO_FULLSCREEN
    initialSelStart = 0
    initialSelEnd = 0
}

private fun KeyEvent.toEditCommand(): EditCommand? = when (keyCode) {
    KeyEvent.KEYCODE_DEL -> BackspaceCommand()
    KeyEvent.KEYCODE_ENTER -> CommitTextCommand("\n", 1)
    else -> unicodeChar.takeIf { it != 0 }?.let {
        CommitTextCommand(String(Character.toChars(it)), 1)
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

    override fun inputText(text: String) {
        // Keep Enter as CR before the translator's single-code-point fast path; LF is
        // accepted by line-oriented shells but is not the terminal Enter byte in raw-mode TUIs.
        translator.sendText(text.replace('\n', '\r'))
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
        if (delta == 0) return
        val result = translator.submit(TerminalCommand.CursorMove(delta))
        if (result is TerminalCommandResult.Success) return

        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(abs(delta)) {
            translator.submit(TerminalCommand.Key(keyCode = keyCode, metaState = 0, down = true))
        }
    }
}

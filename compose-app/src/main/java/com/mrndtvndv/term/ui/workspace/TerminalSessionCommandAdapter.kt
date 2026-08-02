package com.mrndtvndv.term.ui.workspace

import android.view.KeyEvent
import android.view.MotionEvent
import com.termux.terminal.GhosttyMouseEvent
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import com.termux.terminal.compose.TerminalPointerEvent
import kotlin.math.roundToInt

/** Translates neutral canvas commands to the existing Ghostty session API. */
internal class TerminalSessionCommandAdapter(
    private val session: TerminalSession,
    private val view: ComposeInputTerminalView
) {
    fun submit(command: TerminalCommand): TerminalCommandResult = when (command) {
        is TerminalCommand.Text -> writeText(command.text)
        is TerminalCommand.Key -> submitKey(command)
        is TerminalCommand.Mouse -> submitMouse(command.event)
        is TerminalCommand.Scroll -> submitScroll(command)
        is TerminalCommand.SetViewportTopRow -> setViewportTopRow(command.topRow)
    }

    private fun writeText(text: String): TerminalCommandResult {
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            writeCodePoint(codePoint, alt = false)
            index += Character.charCount(codePoint)
        }
        return TerminalCommandResult.Success
    }

    private fun submitKey(command: TerminalCommand.Key): TerminalCommandResult {
        if (!command.down) return TerminalCommandResult.Success
        if (command.keyCode == 0) {
            writeCodePoint(command.codePoint, command.metaState and KeyEvent.META_ALT_ON != 0)
            return TerminalCommandResult.Success
        }

        return KeyHandler.getCode(
            command.keyCode,
            keyModifiers(command.metaState),
            session.isCursorKeysApplicationMode,
            session.isKeypadApplicationMode
        )?.let { code ->
            session.setCursorBlinkState(true)
            session.write(code)
            TerminalCommandResult.Success
        } ?: TerminalCommandResult.Unsupported("Key is handled through Unicode input")
    }

    private fun submitMouse(event: TerminalPointerEvent): TerminalCommandResult {
        val mouseEvent = GhosttyMouseEvent(
            when (event.action) {
                TerminalPointerEvent.Action.PRESS -> GhosttyMouseEvent.PRESS
                TerminalPointerEvent.Action.RELEASE -> GhosttyMouseEvent.RELEASE
                TerminalPointerEvent.Action.MOTION -> GhosttyMouseEvent.MOTION
            },
            when (event.button) {
                TerminalPointerEvent.BUTTON_LEFT -> GhosttyMouseEvent.BUTTON_LEFT
                TerminalPointerEvent.BUTTON_MIDDLE -> GhosttyMouseEvent.BUTTON_MIDDLE
                TerminalPointerEvent.BUTTON_RIGHT -> GhosttyMouseEvent.BUTTON_RIGHT
                else -> GhosttyMouseEvent.BUTTON_NONE
            },
            mouseModifiers(event.modifiers),
            event.xPx,
            event.yPx,
            event.viewportWidthPx,
            event.viewportHeightPx,
            event.cellWidthPx.roundToInt().coerceAtLeast(1),
            event.cellHeightPx.roundToInt().coerceAtLeast(1),
            event.lineSpacingAndAscentPx.roundToInt(),
            0,
            0,
            0
        )
        session.sendGhosttyMouseEvent(mouseEvent)
        return TerminalCommandResult.Success
    }

    private fun submitScroll(command: TerminalCommand.Scroll): TerminalCommandResult {
        if (command.rowsDown == 0) return TerminalCommandResult.Success

        val time = android.os.SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            time,
            time,
            MotionEvent.ACTION_MOVE,
            command.xPx,
            command.yPx,
            0
        )
        try {
            view.doScroll(event, command.rowsDown)
        } finally {
            event.recycle()
        }
        return TerminalCommandResult.Success
    }

    private fun setViewportTopRow(topRow: Int): TerminalCommandResult {
        val clamped = topRow.coerceIn(-session.getActiveTranscriptRows(), 0)
        view.setTopRow(clamped)
        session.setGhosttyTopRow(clamped)
        view.invalidate()
        return TerminalCommandResult.Success
    }

    private fun writeCodePoint(codePoint: Int, alt: Boolean) {
        session.setCursorBlinkState(true)
        session.writeCodePoint(alt, codePoint)
    }

    private fun keyModifiers(metaState: Int): Int {
        var modifiers = 0
        if (metaState and KeyEvent.META_CTRL_ON != 0) modifiers = modifiers or KeyHandler.KEYMOD_CTRL
        if (metaState and KeyEvent.META_ALT_ON != 0) modifiers = modifiers or KeyHandler.KEYMOD_ALT
        if (metaState and KeyEvent.META_SHIFT_ON != 0) modifiers = modifiers or KeyHandler.KEYMOD_SHIFT
        if (metaState and KeyEvent.META_NUM_LOCK_ON != 0) modifiers = modifiers or KeyHandler.KEYMOD_NUM_LOCK
        return modifiers
    }

    private fun mouseModifiers(modifiers: Int): Int {
        var result = 0
        if (modifiers and TerminalPointerEvent.MODIFIER_SHIFT != 0) {
            result = result or GhosttyMouseEvent.MODIFIER_SHIFT
        }
        if (modifiers and TerminalPointerEvent.MODIFIER_ALT != 0) {
            result = result or GhosttyMouseEvent.MODIFIER_ALT
        }
        if (modifiers and TerminalPointerEvent.MODIFIER_CTRL != 0) {
            result = result or GhosttyMouseEvent.MODIFIER_CTRL
        }
        return result
    }
}

package com.termux.terminal.compose.internal

import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult

/**
 * Translates platform input into [TerminalCommand]s.
 *
 * Hardware keys are submitted first as key-code commands; when the backend has
 * no mapping for a key, the unicode-code-point path resolves the character
 * (with the consumer's sticky modifiers and combining accents) and resubmits.
 * The backend adapter owns `KeyHandler`-style escape translation; this file
 * owns only generic terminal input policy.
 */
internal class TerminalInputTranslator(
    private val modifierKeys: ModifierKeyReader,
    onCodePoint: ((Int, Boolean, Boolean) -> Boolean)? = null,
    val submit: (TerminalCommand) -> TerminalCommandResult
) {
    private var combiningAccent = 0
    private var onCodePoint = onCodePoint

    fun updateOnCodePoint(callback: ((Int, Boolean, Boolean) -> Boolean)?) {
        onCodePoint = callback
    }

    /** Returns true when the event was consumed. */
    fun handleKeyEvent(nativeEvent: KeyEvent): Boolean {
        // The back key belongs to system navigation, never the terminal.
        if (nativeEvent.action != KeyEvent.ACTION_DOWN || nativeEvent.keyCode == KeyEvent.KEYCODE_BACK) {
            return false
        }

        val keyCode = nativeEvent.keyCode

        val ctrl = nativeEvent.isCtrlPressed || modifierKeys.readControl()
        val alt = nativeEvent.isAltPressed || modifierKeys.readAlt()
        val shift = nativeEvent.isShiftPressed || modifierKeys.readShift()
        val fn = modifierKeys.readFn()
        val numLock = nativeEvent.isNumLockOn

        var metaState = 0
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON
        if (alt) metaState = metaState or KeyEvent.META_ALT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON
        if (fn) metaState = metaState or KeyEvent.META_FUNCTION_ON
        if (numLock) metaState = metaState or KeyEvent.META_NUM_LOCK_ON

        try {
            val result = submit(TerminalCommand.Key(keyCode = keyCode, metaState = metaState, down = true))
            if (result is TerminalCommandResult.Success) return true

            return submitUnicodeChar(nativeEvent, metaState, ctrl, alt, fn)
        } finally {
            modifierKeys.clearConsumedModifiers()
        }
    }

    /** Sends one code point (IME or resolved key) through the backend. */
    fun sendCodePoint(codePoint: Int, alt: Boolean, controlHeld: Boolean = modifierKeys.readControl()) {
        try {
            submitCodePoint(applyStickyShift(codePoint), alt, controlHeld)
        } finally {
            modifierKeys.clearConsumedModifiers()
        }
    }

    fun sendText(text: String, controlHeld: Boolean? = null) {
        if (text.isEmpty()) return
        val effectiveControlHeld = controlHeld ?: modifierKeys.readControl()
        val codePointCount = text.codePointCount(0, text.length)
        when {
            !effectiveControlHeld && codePointCount == 1 -> {
                sendCodePoint(text.codePointAt(0), alt = false, controlHeld = false)
            }
            !effectiveControlHeld && onCodePoint == null -> {
                submitText(text)
            }
            else -> {
                sendCodePointSequence(text, effectiveControlHeld)
            }
        }
    }

    private fun submitText(text: String) {
        try {
            submit(TerminalCommand.Text(text))
        } finally {
            modifierKeys.clearConsumedModifiers()
        }
    }

    private fun sendCodePointSequence(text: String, controlHeld: Boolean) {
        try {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                submitCodePoint(
                    applyStickyShift(codePoint),
                    alt = false,
                    controlHeld = controlHeld
                )
                index += Character.charCount(codePoint)
            }
        } finally {
            modifierKeys.clearConsumedModifiers()
        }
    }

    private fun submitCodePoint(codePoint: Int, alt: Boolean, controlHeld: Boolean) {
        val effectiveAlt = alt || modifierKeys.readAlt()
        if (onCodePoint?.invoke(codePoint, controlHeld, effectiveAlt) == true) return

        submit(
            TerminalCommand.Key(
                keyCode = 0,
                metaState = if (effectiveAlt) KeyEvent.META_ALT_ON else 0,
                down = true,
                codePoint = applyControlMapping(codePoint, controlHeld)
            )
        )
    }

    private fun applyStickyShift(codePoint: Int): Int =
        if (modifierKeys.readShift()) Character.toUpperCase(codePoint) else codePoint

    private fun submitUnicodeChar(
        nativeEvent: KeyEvent,
        metaState: Int,
        ctrl: Boolean,
        alt: Boolean,
        fn: Boolean
    ): Boolean {
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if ((nativeEvent.metaState and KeyEvent.META_ALT_RIGHT_ON) == 0) {
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        var effectiveMetaState = nativeEvent.metaState and bitsToClear.inv()
        if (modifierKeys.readShift()) {
            effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (fn) {
            effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON
        }

        var result = nativeEvent.getUnicodeChar(effectiveMetaState)
        if (result == 0) return false

        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            if (combiningAccent != 0) {
                sendCodePoint(combiningAccent, alt)
            }
            combiningAccent = result and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }

        var finalResult = result
        if (combiningAccent != 0) {
            val combinedChar = KeyCharacterMap.getDeadChar(combiningAccent, result)
            if (combinedChar > 0) {
                finalResult = combinedChar
            }
            combiningAccent = 0
        }
        submitCodePoint(finalResult, alt, ctrl)
        return true
    }
}

/** Port of the emulator's control-key translation for character input. */
internal fun applyControlMapping(codePoint: Int, controlHeld: Boolean): Int {
    if (!controlHeld) return codePoint
    return when (codePoint) {
        in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        ' '.code, '2'.code -> 0
        '['.code, '3'.code -> 27
        '\\'.code, '4'.code -> 28
        ']'.code, '5'.code -> 29
        '^'.code, '6'.code -> 30
        '_'.code, '7'.code, '/'.code -> 31
        '8'.code -> 127
        else -> codePoint
    }
}

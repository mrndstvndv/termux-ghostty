package com.mrndtvndv.term.ui.keyboard

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class ExtraKeyEncodingOptions(
    val cursorKeysApplicationMode: Boolean,
    val keypadApplicationMode: Boolean,
    val kittyKeyboardFlags: Int
)

@Composable
fun ExtraKeysToolbar(
    extraKeysController: ExtraKeysController,
    session: TerminalSession?,
    extraKeysJson: String?,
    modifier: Modifier = Modifier
) {
    val extraKeysInfo = remember(extraKeysJson) {
        try {
            if (!extraKeysJson.isNullOrBlank()) {
                ExtraKeysInfo(extraKeysJson, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES)
            } else {
                ExtraKeysInfo(
                    PresetDoubleRow,
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES
                )
            }
        } catch (e: Exception) {
            // Safe fallback
            try {
                ExtraKeysInfo(
                    PresetDoubleRow,
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES
                )
            } catch (e2: Exception) {
                null
            }
        }
    }

    if (extraKeysInfo == null) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val matrix = extraKeysInfo.matrix
        for (row in matrix) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (buttonInfo in row) {
                    ExtraKeyButtonComponent(
                        buttonInfo = buttonInfo,
                        extraKeysController = extraKeysController,
                        session = session,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
fun ExtraKeyButtonComponent(
    buttonInfo: ExtraKeyButton,
    extraKeysController: ExtraKeysController,
    session: TerminalSession?,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }

    val isModifier = buttonInfo.key == "CTRL" || buttonInfo.key == "ALT" || buttonInfo.key == "SHIFT" || buttonInfo.key == "FN"

    val modifierState = when (buttonInfo.key) {
        "CTRL" -> extraKeysController.ctrlState
        "ALT" -> extraKeysController.altState
        "SHIFT" -> extraKeysController.shiftState
        "FN" -> extraKeysController.fnState
        else -> ModifierState.INACTIVE
    }

    val isActive = modifierState != ModifierState.INACTIVE
    val isLocked = modifierState == ModifierState.LOCKED

    fun onModifierClick() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (buttonInfo.key) {
            "CTRL" -> extraKeysController.toggleControl()
            "ALT" -> extraKeysController.toggleAlt()
            "SHIFT" -> extraKeysController.toggleShift()
            "FN" -> extraKeysController.toggleFn()
        }
    }

    fun onModifierLongClick() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when (buttonInfo.key) {
            "CTRL" -> extraKeysController.lockControl()
            "ALT" -> extraKeysController.lockAlt()
            "SHIFT" -> extraKeysController.lockShift()
            "FN" -> extraKeysController.lockFn()
        }
    }

    // Determine colors
    val backgroundColor = when {
        isLocked -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isPressed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    val textColor = when {
        isLocked -> MaterialTheme.colorScheme.onPrimary
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderModifier = if (isLocked) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(borderModifier)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(buttonInfo) {
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            isPressed = true
                            var isLongPressed = false
                            var isSwipedUp = false
                            showPopup = false

                            val longPressJob = launch {
                                delay(400) // Default Android long press timeout is 400ms
                                isLongPressed = true
                                if (isModifier) {
                                    onModifierLongClick()
                                } else if (ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS.contains(buttonInfo.key)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    // Repeat action
                                    while (true) {
                                        if (session != null) {
                                            dispatchExtraKey(buttonInfo, extraKeysController, session)
                                        }
                                        delay(80) // Repeat delay (80ms)
                                    }
                                }
                            }

                            var pointerId = down.id
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null) {
                                    active = false
                                    longPressJob.cancel()
                                    isPressed = false
                                    showPopup = false
                                } else if (change.pressed) {
                                    // Check if dragged above button
                                    val isAbove = change.position.y < 0
                                    if (buttonInfo.popup != null) {
                                        if (isAbove && !isSwipedUp) {
                                            isSwipedUp = true
                                            showPopup = true
                                            longPressJob.cancel()
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        } else if (!isAbove && isSwipedUp) {
                                            isSwipedUp = false
                                            showPopup = false
                                        }
                                    }
                                    change.consume()
                                } else {
                                    // Up event (released)
                                    active = false
                                    longPressJob.cancel()
                                    isPressed = false
                                    showPopup = false

                                    if (isSwipedUp && buttonInfo.popup != null) {
                                        if (session != null) {
                                            dispatchExtraKey(buttonInfo.popup!!, extraKeysController, session)
                                        }
                                    } else if (!isLongPressed || isModifier) {
                                        if (isModifier) {
                                            if (!isLongPressed) {
                                                onModifierClick()
                                            }
                                        } else {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (session != null) {
                                                dispatchExtraKey(buttonInfo, extraKeysController, session)
                                            }
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = buttonInfo.display,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )

        if (showPopup && buttonInfo.popup != null) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = androidx.compose.ui.unit.IntOffset(0, -110)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = buttonInfo.popup!!.display,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

fun dispatchExtraKey(
    buttonInfo: ExtraKeyButton,
    extraKeysController: ExtraKeysController,
    session: TerminalSession
) {
    val key = buttonInfo.key
    if ("PASTE" == key) {
        session.onPasteTextFromClipboard()
        return
    }
    // Treat as macro if: the ExtraKeyButton is explicitly a macro ({macro: '...'}),
    // OR it's a plain string key that contains spaces (e.g. 'CTRL b n') — convenience format.
    val isMacro = buttonInfo.isMacro ||
        (key.contains(" ") && !ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key))

    if (isMacro) {
        val sequence = encodeExtraKeyMacro(
            macro = key,
            cursorKeysApplicationMode = session.isCursorKeysApplicationMode,
            keypadApplicationMode = session.isKeypadApplicationMode,
            kittyKeyboardFlags = session.getKittyKeyboardFlags()
        )
        if (sequence.isNotEmpty()) {
            session.setCursorBlinkState(true)
            session.write(sequence)
        }
    } else {
        // Single key: read modifier state from the extra keys controller toggle buttons,
        // consuming the active (non-locked) state in the process.
        val ctrl = extraKeysController.readControl()
        val alt = extraKeysController.readAlt()
        val shift = extraKeysController.readShift()
        val fn = extraKeysController.readFn()
        try {
            sendSingleKey(key, ctrl, alt, shift, fn, session)
        } finally {
            extraKeysController.clearConsumedModifiers()
        }
    }
}

internal fun encodeExtraKeyMacro(
    macro: String,
    cursorKeysApplicationMode: Boolean,
    keypadApplicationMode: Boolean,
    kittyKeyboardFlags: Int = 0
): String {
    val sequence = StringBuilder()
    val encodingOptions = ExtraKeyEncodingOptions(
        cursorKeysApplicationMode = cursorKeysApplicationMode,
        keypadApplicationMode = keypadApplicationMode,
        kittyKeyboardFlags = kittyKeyboardFlags
    )
    var ctrl = false
    var alt = false
    var shift = false
    var fn = false
    for (part in macro.split(" ")) {
        when (part.uppercase()) {
            "CTRL", "CONTROL" -> ctrl = true
            "ALT" -> alt = true
            "SHIFT", "SHFT" -> shift = true
            "FN", "FUNCTION" -> fn = true
            else -> {
                sequence.append(
                    encodeExtraKeyPart(
                        key = part,
                        ctrl = ctrl,
                        alt = alt,
                        shift = shift,
                        fn = fn,
                        options = encodingOptions
                    )
                )
                ctrl = false
                alt = false
                shift = false
                fn = false
            }
        }
    }
    return sequence.toString()
}

private fun encodeExtraKeyPart(
    key: String,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    fn: Boolean,
    options: ExtraKeyEncodingOptions
): String {
    val keyCode = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS[key]
    val keyCodeSequence = if (keyCode != null && !fn) {
        var keyModifiers = 0
        if (ctrl) keyModifiers = keyModifiers or KeyHandler.KEYMOD_CTRL
        if (alt) keyModifiers = keyModifiers or KeyHandler.KEYMOD_ALT
        if (shift) keyModifiers = keyModifiers or KeyHandler.KEYMOD_SHIFT
        KeyHandler.getCode(
            keyCode,
            keyModifiers,
            options.cursorKeysApplicationMode,
            options.keypadApplicationMode,
            options.kittyKeyboardFlags
        ).orEmpty()
    } else {
        null
    }
    if (keyCodeSequence != null) return keyCodeSequence
    if (key.length != 1) return key
    var codePoint = key.codePointAt(0)
    var isUpperCase = codePoint in 'A'.code..'Z'.code
    if (shift && codePoint in 'a'.code..'z'.code) {
        codePoint -= 'a'.code - 'A'.code
        isUpperCase = true
    }
    val textSequence = if (ctrl && isUpperCase && shift) {
        val modifier = if (alt) 14 else 6
        "\u001b[${codePoint};${modifier}u"
    } else {
        val mappedCodePoint = if (ctrl) controlCodePoint(codePoint) else codePoint
        buildString {
            if (alt) append('\u001b')
            append(String(Character.toChars(mappedCodePoint)))
        }
    }
    return textSequence
}

private fun controlCodePoint(codePoint: Int): Int = when (codePoint) {
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

private fun sendSingleKey(
    key: String,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    fn: Boolean,
    session: TerminalSession
) {
    val sequence = encodeExtraKeyPart(
        key = key,
        ctrl = ctrl,
        alt = alt,
        shift = shift,
        fn = fn,
        options = ExtraKeyEncodingOptions(
            cursorKeysApplicationMode = session.isCursorKeysApplicationMode,
            keypadApplicationMode = session.isKeypadApplicationMode,
            kittyKeyboardFlags = session.getKittyKeyboardFlags()
        )
    )
    if (sequence.isNotEmpty()) {
        session.setCursorBlinkState(true)
        session.write(sequence)
    }
}

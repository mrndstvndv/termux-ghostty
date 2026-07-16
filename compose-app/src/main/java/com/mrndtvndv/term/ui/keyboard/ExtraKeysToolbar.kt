package com.mrndtvndv.term.ui.keyboard

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent as AndroidKeyEvent
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
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ExtraKeysToolbar(
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
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
                    "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]",
                    "default",
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES
                )
            }
        } catch (e: Exception) {
            // Safe fallback
            try {
                ExtraKeysInfo(
                    "[['ESC','/',{key: '-', popup: '|'},'HOME','UP','END','PGUP'], ['TAB','CTRL','ALT','LEFT','DOWN','RIGHT','PGDN']]",
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
                        getActiveTerminalView = getActiveTerminalView,
                        session = session,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
fun ExtraKeyButtonComponent(
    buttonInfo: ExtraKeyButton,
    extraKeysController: ExtraKeysController,
    getActiveTerminalView: () -> TerminalView?,
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
                                            dispatchExtraKey(buttonInfo, extraKeysController, getActiveTerminalView(), session)
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
                                            dispatchExtraKey(buttonInfo.popup!!, extraKeysController, getActiveTerminalView(), session)
                                        }
                                    } else if (!isLongPressed || isModifier) {
                                        if (isModifier) {
                                            if (!isLongPressed) {
                                                onModifierClick()
                                            }
                                        } else {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (session != null) {
                                                dispatchExtraKey(buttonInfo, extraKeysController, getActiveTerminalView(), session)
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
    activeTerminalView: TerminalView?,
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
        // Macro: split by space, accumulate modifiers, fire on non-modifier tokens.
        // This matches the legacy TerminalExtraKeys.java behavior exactly.
        val parts = key.split(" ")
        var ctrl = false
        var alt = false
        var shift = false
        var fn = false
        for (part in parts) {
            when (part.uppercase()) {
                "CTRL", "CONTROL" -> ctrl = true
                "ALT" -> alt = true
                "SHIFT", "SHFT" -> shift = true
                "FN", "FUNCTION" -> fn = true
                else -> {
                    sendSingleKey(part, ctrl, alt, shift, fn, activeTerminalView, session)
                    ctrl = false; alt = false; shift = false; fn = false
                }
            }
        }
    } else {
        // Single key: read modifier state from the extra keys controller toggle buttons,
        // consuming the active (non-locked) state in the process.
        val ctrl = extraKeysController.readControl()
        val alt = extraKeysController.readAlt()
        val shift = extraKeysController.readShift()
        val fn = extraKeysController.readFn()
        sendSingleKey(key, ctrl, alt, shift, fn, activeTerminalView, session)
    }
}

private fun sendSingleKey(
    key: String,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    fn: Boolean,
    activeTerminalView: TerminalView?,
    session: TerminalSession
) {
    if (ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key)) {
        val keyCode = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS[key] ?: return
        var metaState = 0
        if (ctrl) metaState = metaState or (AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON)
        if (alt) metaState = metaState or (AndroidKeyEvent.META_ALT_ON or AndroidKeyEvent.META_ALT_LEFT_ON)
        if (shift) metaState = metaState or (AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON)
        if (fn) metaState = metaState or AndroidKeyEvent.META_FUNCTION_ON

        val keyEvent = AndroidKeyEvent(0, 0, AndroidKeyEvent.ACTION_UP, keyCode, 0, metaState)
        if (activeTerminalView != null) {
            activeTerminalView.onKeyDown(keyCode, keyEvent)
        } else {
            val escapeCode = when (key) {
                "ESC" -> "\u001b"
                "TAB" -> "\t"
                "LEFT" -> "\u001b[D"
                "UP" -> "\u001b[A"
                "DOWN" -> "\u001b[B"
                "RIGHT" -> "\u001b[C"
                "ENTER" -> "\r"
                "BKSP" -> "\u007f"
                else -> null
            }
            if (escapeCode != null) {
                session.write(escapeCode)
            }
        }
    } else {
        // Resolve codePoint and apply control character mapping directly if ctrl is true
        val codePoint = if (key.length == 1) key.codePointAt(0) else -1
        if (codePoint != -1) {
            var finalCodePoint = codePoint
            if (ctrl) {
                if (finalCodePoint in 'a'.code..'z'.code) {
                    finalCodePoint = finalCodePoint - 'a'.code + 1
                } else if (finalCodePoint in 'A'.code..'Z'.code) {
                    finalCodePoint = finalCodePoint - 'A'.code + 1
                } else if (finalCodePoint == ' '.code || finalCodePoint == '2'.code) {
                    finalCodePoint = 0
                } else if (finalCodePoint == '['.code || finalCodePoint == '3'.code) {
                    finalCodePoint = 27
                } else if (finalCodePoint == '\\'.code || finalCodePoint == '4'.code) {
                    finalCodePoint = 28
                } else if (finalCodePoint == ']'.code || finalCodePoint == '5'.code) {
                    finalCodePoint = 29
                } else if (finalCodePoint == '^'.code || finalCodePoint == '6'.code) {
                    finalCodePoint = 30
                } else if (finalCodePoint == '_'.code || finalCodePoint == '7'.code || finalCodePoint == '/'.code) {
                    finalCodePoint = 31
                } else if (finalCodePoint == '8'.code) {
                    finalCodePoint = 127
                }
            }

            if (activeTerminalView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    // Pass false for controlDownFromEvent because finalCodePoint is already converted
                    activeTerminalView.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, finalCodePoint, false, alt)
                } else {
                    session.writeCodePoint(alt, finalCodePoint)
                }
            } else {
                session.writeCodePoint(alt, finalCodePoint)
            }
        } else {
            session.write(key)
        }
    }
}

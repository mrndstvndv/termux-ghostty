package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.terminal.TerminalSession
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalConstants
import com.termux.terminal.GhosttyMouseEvent
import com.termux.view.TerminalRenderer
import java.io.File

fun getValidCurX(content: com.termux.terminal.TerminalContent, cy: Int, cx: Int): Int {
    val line = content.getSelectedText(0, cy, cx, cy)
    if (!line.isNullOrEmpty()) {
        var col = 0
        var i = 0
        val len = line.length
        while (i < len) {
            val ch1 = line[i]
            if (ch1.code == 0) {
                break
            }
            val wc: Int
            if (Character.isHighSurrogate(ch1) && i + 1 < len) {
                val ch2 = line[++i]
                wc = com.termux.terminal.WcWidth.width(Character.toCodePoint(ch1, ch2))
            } else {
                wc = com.termux.terminal.WcWidth.width(ch1.code)
            }
            val cend = col + wc
            if (cx > col && cx < cend) {
                return cend
            }
            if (cend == col) {
                return col
            }
            col = cend
            i++
        }
    }
    return cx
}

fun isWhitespaceCell(content: com.termux.terminal.TerminalContent, column: Int, row: Int): Boolean {
    val cellText = content.getSelectedText(column, row, column, row)
    if (cellText.isNullOrEmpty()) return true
    var index = 0
    while (index < cellText.length) {
        val codePoint = cellText.codePointAt(index)
        if (!Character.isWhitespace(codePoint)) return false
        index += Character.charCount(codePoint)
    }
    return true
}

fun inputCodePoint(
    codePoint: Int,
    ctrlHeldFromEvent: Boolean,
    altHeldFromEvent: Boolean,
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController
) {
    session.setCursorBlinkState(true)

    val ctrlHeld = ctrlHeldFromEvent || extraKeysController.readControl()
    val altHeld = altHeldFromEvent || extraKeysController.readAlt()

    var cp = codePoint
    if (ctrlHeld) {
        if (cp >= 'a'.code && cp <= 'z'.code) {
            cp = cp - 'a'.code + 1
        } else if (cp >= 'A'.code && cp <= 'Z'.code) {
            cp = cp - 'A'.code + 1
        } else if (cp == ' '.code || cp == '2'.code) {
            cp = 0
        } else if (cp == '['.code || cp == '3'.code) {
            cp = 27 // Esc
        } else if (cp == '\\'.code || cp == '4'.code) {
            cp = 28
        } else if (cp == ']'.code || cp == '5'.code) {
            cp = 29
        } else if (cp == '^'.code || cp == '6'.code) {
            cp = 30
        } else if (cp == '_'.code || cp == '7'.code || cp == '/'.code) {
            cp = 31
        } else if (cp == '8'.code) {
            cp = 127 // DEL
        }
    }

    if (cp > -1) {
        session.writeCodePoint(altHeld, cp)
    }
}

private fun sendTouchAsMouseClick(
    session: TerminalSession,
    inputView: ComposeInputTerminalView,
    x: Float,
    y: Float
) {
    val renderer = inputView.mRenderer ?: return
    if (!session.hasActiveTerminalBackend()) return
    if (!session.isMouseTrackingActive()) return

    val cellW = Math.max(1, Math.round(renderer.getFontWidth()))
    val cellH = Math.max(1, renderer.getFontLineSpacing())

    val press = GhosttyMouseEvent(
        GhosttyMouseEvent.PRESS,
        GhosttyMouseEvent.BUTTON_LEFT,
        0, x, y,
        inputView.width, inputView.height,
        cellW, cellH,
        renderer.mFontLineSpacingAndAscent,
        0, 0, 0
    )
    session.sendGhosttyMouseEvent(press)

    val release = GhosttyMouseEvent(
        GhosttyMouseEvent.RELEASE,
        GhosttyMouseEvent.BUTTON_LEFT,
        0, x, y,
        inputView.width, inputView.height,
        cellW, cellH,
        renderer.mFontLineSpacingAndAscent,
        0, 0, 0
    )
    session.sendGhosttyMouseEvent(release)
}

private fun sendTouchAsMouseMove(
    session: TerminalSession,
    inputView: ComposeInputTerminalView,
    x: Float,
    y: Float
) {
    val renderer = inputView.mRenderer ?: return
    if (!session.hasActiveTerminalBackend()) return
    if (!session.isMouseTrackingActive()) return

    val cellW = Math.max(1, Math.round(renderer.getFontWidth()))
    val cellH = Math.max(1, renderer.getFontLineSpacing())

    val move = GhosttyMouseEvent(
        GhosttyMouseEvent.MOTION,
        GhosttyMouseEvent.BUTTON_LEFT,
        0, x, y,
        inputView.width, inputView.height,
        cellW, cellH,
        renderer.mFontLineSpacingAndAscent,
        0, 0, 0
    )
    session.sendGhosttyMouseEvent(move)
}

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController,
    onOpenUrl: (String) -> Unit,
    onViewCreated: (com.termux.view.TerminalView) -> Unit,
    onViewReleased: (com.termux.view.TerminalView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val hapticFeedback = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    val sharedPreferences = remember(context) { context.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE) }
    val sizes = remember(context) { TermuxAppSharedPreferences.getDefaultFontSizes(context) }
    val currentFontSize = remember { mutableStateOf(sharedPreferences.getInt("font_size", sizes[0])) }

    var frameTrigger by remember { mutableStateOf(0) }

    val inputView = remember(session) {
        val customFontFile = File(context.filesDir, "font.ttf")
        val face = if (customFontFile.exists() && customFontFile.length() > 0) {
            try { Typeface.createFromFile(customFontFile) } catch (e: Exception) { Typeface.MONOSPACE }
        } else { Typeface.MONOSPACE }

        ComposeInputTerminalView(context).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setTextSize(currentFontSize.value)
            setTypeface(face)

            setTerminalViewClient(object : com.termux.shared.termux.terminal.TermuxTerminalViewClientBase() {
                override fun onSingleTapUp(e: MotionEvent) {
                    val url = getTerminalTranscriptUrlOnTap(e)
                    if (url != null) {
                        onOpenUrl(url)
                        return
                    }
                    this@apply.requestFocus()
                }
                override fun shouldOpenTerminalTranscriptURLOnClick() = true
                override fun getTerminalTranscriptUrlOnTap(e: MotionEvent) = getVisibleLinkHit(e)?.url
                override fun onScale(scale: Float): Float {
                    if (scale < 0.9f || scale > 1.1f) {
                        val increase = scale > 1f
                        val currentSize = this@apply.mRenderer.mTextSize
                        val newSize = currentSize + (if (increase) 1 else -1) * 2
                        val clampedSize = newSize.coerceIn(sizes[1], sizes[2])
                        if (clampedSize != currentSize) {
                            this@apply.setTextSize(clampedSize)
                            currentFontSize.value = clampedSize
                            sharedPreferences.edit().putInt("font_size", clampedSize).apply()
                        }
                        return 1f
                    }
                    return scale
                }
                override fun readControlKey(): Boolean = extraKeysController.readControl()
                override fun readAltKey(): Boolean = extraKeysController.readAlt()
                override fun readShiftKey(): Boolean = extraKeysController.readShift()
                override fun readFnKey(): Boolean = extraKeysController.readFn()
            })
            attachSession(session)
        }
    }

    LaunchedEffect(inputView) {
        inputView.onInvalidateCallback = {
            frameTrigger++
        }
    }

    LaunchedEffect(currentFontSize.value) {
        inputView.setTextSize(currentFontSize.value)
    }

    DisposableEffect(session, inputView) {
        onViewCreated(inputView)
        onDispose {
            inputView.detachSession()
            onViewReleased(inputView)
        }
    }

    // Text selection states
    var selectionStartCol by remember { mutableStateOf<Int?>(null) }
    var selectionStartRow by remember { mutableStateOf<Int?>(null) }
    var selectionEndCol by remember { mutableStateOf<Int?>(null) }
    var selectionEndRow by remember { mutableStateOf<Int?>(null) }

    val isSelectingText = remember {
        derivedStateOf {
            selectionStartCol != null && selectionStartRow != null && selectionEndCol != null && selectionEndRow != null
        }
    }

    var combiningAccent by remember { mutableIntStateOf(0) }

    // Screen text snapshot for screen readers
    val visibleText = remember(session, frameTrigger) {
        val top = inputView.getTopRow()
        val text = session.getSelectedText(0, top, session.columns, top + session.rows)
        text ?: ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                inputView.layout(0, 0, size.width, size.height)
                inputView.updateSize(true)
            }
    ) {
        // Native Keyboard Input (Hidden BasicTextField)
        var textFieldValue by remember { mutableStateOf(TextFieldValue("  ", selection = androidx.compose.ui.text.TextRange(2))) }

        BasicTextField(
            value = textFieldValue,
            onValueChange = { newValue ->
                val textVal = newValue.text
                if (textVal.length == 2) {
                    val selStart = newValue.selection.start
                    if (selStart != 2) {
                        val diff = selStart - 2
                        if (diff < 0) {
                            repeat(Math.abs(diff)) {
                                val code = KeyHandler.getCode(
                                    KeyEvent.KEYCODE_DPAD_LEFT,
                                    0,
                                    session.isCursorKeysApplicationMode,
                                    session.isKeypadApplicationMode
                                )
                                if (code != null) session.write(code)
                            }
                        } else {
                            repeat(diff) {
                                val code = KeyHandler.getCode(
                                    KeyEvent.KEYCODE_DPAD_RIGHT,
                                    0,
                                    session.isCursorKeysApplicationMode,
                                    session.isKeypadApplicationMode
                                )
                                if (code != null) session.write(code)
                            }
                        }
                    }
                }

                if (textVal.length < 2) {
                    val diff = 2 - textVal.length
                    repeat(diff) {
                        val code = KeyHandler.getCode(
                            KeyEvent.KEYCODE_DEL,
                            0,
                            session.isCursorKeysApplicationMode,
                            session.isKeypadApplicationMode
                        )
                        if (code != null) {
                            session.write(code)
                        } else {
                            session.writeCodePoint(false, 127) // DEL
                        }
                    }
                } else if (textVal.length > 2) {
                    val addedText = textVal.substring(2)
                    for (char in addedText) {
                        val ctrlHeld = extraKeysController.readControl()
                        val altHeld = extraKeysController.readAlt()
                        val codePoint = if (char == '\n') 13 else char.code
                        inputCodePoint(codePoint, ctrlHeld, altHeld, session, extraKeysController)
                    }
                }
                textFieldValue = TextFieldValue("  ", selection = androidx.compose.ui.text.TextRange(2))
            },
            keyboardOptions = KeyboardOptions.Default,
            modifier = Modifier
                .size(1.dp)
                .alpha(0f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { keyEvent ->
                    val nativeEvent = keyEvent.nativeKeyEvent
                    if (keyEvent.type == KeyEventType.KeyDown) {
                        val keyCode = nativeEvent.keyCode
                        if (isSelectingText.value) {
                            selectionStartCol = null
                            selectionStartRow = null
                            selectionEndCol = null
                            selectionEndRow = null
                        }

                        val ctrl = nativeEvent.isCtrlPressed || extraKeysController.readControl()
                        val alt = nativeEvent.isAltPressed || extraKeysController.readAlt()
                        val shift = nativeEvent.isShiftPressed || extraKeysController.readShift()
                        val numLock = nativeEvent.isNumLockOn

                        var keyMod = 0
                        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
                        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
                        if (shift) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
                        if (numLock) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

                        val code = KeyHandler.getCode(
                            keyCode,
                            keyMod,
                            session.isCursorKeysApplicationMode,
                            session.isKeypadApplicationMode
                        )
                        if (code != null) {
                            session.write(code)
                            return@onPreviewKeyEvent true
                        }

                        var bitsToClear = KeyEvent.META_CTRL_MASK
                        if ((nativeEvent.metaState and KeyEvent.META_ALT_RIGHT_ON) == 0) {
                            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
                        }
                        var effectiveMetaState = nativeEvent.metaState and bitsToClear.inv()
                        if (shift) {
                            effectiveMetaState = effectiveMetaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                        }
                        if (extraKeysController.readFn()) {
                            effectiveMetaState = effectiveMetaState or KeyEvent.META_FUNCTION_ON
                        }

                        var result = nativeEvent.getUnicodeChar(effectiveMetaState)
                        if (result != 0) {
                            if ((result and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
                                if (combiningAccent != 0) {
                                    inputCodePoint(combiningAccent, ctrl, alt, session, extraKeysController)
                                }
                                combiningAccent = result and android.view.KeyCharacterMap.COMBINING_ACCENT_MASK
                            } else {
                                var finalResult = result
                                if (combiningAccent != 0) {
                                    val combinedChar = android.view.KeyCharacterMap.getDeadChar(combiningAccent, result)
                                    if (combinedChar > 0) {
                                        finalResult = combinedChar
                                    }
                                    combiningAccent = 0
                                }
                                inputCodePoint(finalResult, ctrl, alt, session, extraKeysController)
                            }
                            return@onPreviewKeyEvent true
                        }
                    }
                    false
                }
        )

        // Rendering Layer (Compose Canvas)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    text = AnnotatedString(visibleText)
                }
                .pointerInput(inputView, session) {
                    var scaleAccumulator = 1f
                    var dragAccumulator = 0f
                    detectTransformGestures { _, pan, zoom, _ ->
                        if (isSelectingText.value) return@detectTransformGestures

                        val renderer = inputView.mRenderer
                        if (renderer != null) {
                            if (zoom != 1f) {
                                scaleAccumulator *= zoom
                                if (scaleAccumulator < 0.9f || scaleAccumulator > 1.1f) {
                                    val increase = scaleAccumulator > 1f
                                    val currentSize = renderer.mTextSize
                                    val newSize = currentSize + (if (increase) 1 else -1) * 2
                                    val clampedSize = newSize.coerceIn(sizes[1], sizes[2])
                                    if (clampedSize != currentSize) {
                                        currentFontSize.value = clampedSize
                                        sharedPreferences.edit().putInt("font_size", clampedSize).apply()
                                    }
                                    scaleAccumulator = 1f
                                }
                            }

                            // Touch drag always scrolls (same as original GestureAndScaleRecognizer behavior)
                            // MOTION events via touch are only for real mice, never for touch input
                            if (pan.y != 0f) {
                                dragAccumulator += pan.y
                                val fontHeight = renderer.getFontLineSpacing()
                                val deltaRows = (dragAccumulator / fontHeight).toInt()
                                if (deltaRows != 0) {
                                    dragAccumulator -= deltaRows * fontHeight
                                    inputView.doScroll(null, -deltaRows)
                                }
                            }
                        }
                    }
                }
                .pointerInput(inputView, session) {
                    detectTapGestures(
                        onTap = { offset ->
                            if (isSelectingText.value) {
                                selectionStartCol = null
                                selectionStartRow = null
                                selectionEndCol = null
                                selectionEndRow = null
                            } else {
                                // Forward tap as mouse click when terminal mouse tracking is active
                                sendTouchAsMouseClick(session, inputView, offset.x, offset.y)

                                focusRequester.requestFocus()
                                keyboardController?.show()

                                val event = MotionEvent.obtain(
                                    android.os.SystemClock.uptimeMillis(),
                                    android.os.SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_DOWN,
                                    offset.x,
                                    offset.y,
                                    0
                                )
                                val hit = inputView.getVisibleLinkHit(event)
                                event.recycle()
                                if (hit?.url != null) {
                                    onOpenUrl(hit.url)
                                }
                            }
                        },
                        onLongPress = { offset ->
                            if (session.hasActiveTerminalBackend()) {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                                val col = inputView.getCursorX(offset.x)
                                val row = inputView.getCursorY(offset.y)

                                val content = session.terminalContent
                                if (content != null) {
                                    val x = col.coerceIn(0, content.columns - 1)
                                    var x1 = x
                                    var x2 = x
                                    if (!isWhitespaceCell(content, x1, row)) {
                                        while (x1 > 0 && !isWhitespaceCell(content, x1 - 1, row)) {
                                            x1--
                                        }
                                        while (x2 < content.columns - 1 && !isWhitespaceCell(content, x2 + 1, row)) {
                                            x2++
                                        }
                                    }
                                    selectionStartCol = x1
                                    selectionStartRow = row
                                    selectionEndCol = x2
                                    selectionEndRow = row
                                }
                            }
                        }
                    )
                }
        ) {
            val renderer = inputView.mRenderer ?: return@Canvas
            val renderCache = inputView.renderFrameCache ?: return@Canvas

            @Suppress("UNUSED_VARIABLE")
            val trigger = frameTrigger

            val currentSnapshot = renderCache.getSnapshotForRender(
                session.isGhosttyCursorBlinkingEnabled,
                session.ghosttyCursorBlinkState
            ) ?: return@Canvas

            val startY = selectionStartRow ?: -1
            val endY = selectionEndRow ?: -1
            val startX = selectionStartCol ?: -1
            val endX = selectionEndCol ?: -1

            drawIntoCanvas { canvas ->
                renderer.render(
                    currentSnapshot,
                    canvas.nativeCanvas,
                    startY, endY, startX, endX
                )
            }
        }

        // Selection Handles and Action Toolbar Overlay
        if (isSelectingText.value) {
            val startCol = selectionStartCol!!
            val startRow = selectionStartRow!!
            val endCol = selectionEndCol!!
            val endRow = selectionEndRow!!

            val topRow = inputView.getTopRow()

            val startX = inputView.getPointX(startCol).toFloat()
            val startY = inputView.getPointY(startRow).toFloat()

            val endX = inputView.getPointX(endCol + 1).toFloat()
            val endY = inputView.getPointY(endRow).toFloat()

            val leftHandlePainter = painterResource(id = com.termux.view.R.drawable.text_select_handle_left_material)
            val rightHandlePainter = painterResource(id = com.termux.view.R.drawable.text_select_handle_right_material)

            val density = LocalDensity.current
            val handleWidthPx = with(density) { leftHandlePainter.intrinsicSize.width }

            val leftHandleX = startX - handleWidthPx * 0.75f
            val leftHandleY = startY

            val rightHandleX = endX - handleWidthPx * 0.25f
            val rightHandleY = endY

            // Left Selection Handle
            Image(
                painter = leftHandlePainter,
                contentDescription = "Start Selection Handle",
                modifier = Modifier
                    .offset { IntOffset(leftHandleX.toInt(), leftHandleY.toInt()) }
                    .size(with(density) { leftHandlePainter.intrinsicSize.toDpSize() })
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume tap, prevent passthrough to Canvas */ }
                    }
                    .pointerInput(session, inputView) {
                        var accumDragX = 0f
                        var accumDragY = 0f
                        detectDragGestures(
                            onDragStart = {
                                // Read current handle position from live state
                                val sx = inputView.getPointX(selectionStartCol!!).toFloat()
                                val sy = inputView.getPointY(selectionStartRow!!).toFloat()
                                accumDragX = sx
                                accumDragY = sy
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDragX += dragAmount.x
                                accumDragY += dragAmount.y

                                val curX = inputView.getCursorX(accumDragX)
                                val curY = inputView.getCursorY(accumDragY)

                                val content = session.terminalContent
                                if (content != null) {
                                    val scrollRows = content.activeRows - content.rows
                                    var newY1 = curY.coerceIn(-scrollRows, content.rows - 1)
                                    var newX1 = curX.coerceIn(0, content.columns - 1)

                                    val endRow = selectionEndRow!!
                                    val endCol = selectionEndCol!!
                                    if (newY1 > endRow) {
                                        newY1 = endRow
                                    }
                                    if (newY1 == endRow && newX1 > endCol) {
                                        newX1 = endCol
                                    }

                                    if (!content.isAlternateBufferActive) {
                                        var currentTop = inputView.getTopRow()
                                        if (newY1 <= currentTop) {
                                            currentTop = (currentTop - 1).coerceAtLeast(-scrollRows)
                                            inputView.setTopRow(currentTop)
                                        } else if (newY1 >= currentTop + content.rows) {
                                            currentTop = (currentTop + 1).coerceAtMost(0)
                                            inputView.setTopRow(currentTop)
                                        }
                                    }

                                    selectionStartRow = newY1
                                    selectionStartCol = getValidCurX(content, newY1, newX1)
                                }
                            }
                        )
                    }
            )

            // Right Selection Handle
            Image(
                painter = rightHandlePainter,
                contentDescription = "End Selection Handle",
                modifier = Modifier
                    .offset { IntOffset(rightHandleX.toInt(), rightHandleY.toInt()) }
                    .size(with(density) { rightHandlePainter.intrinsicSize.toDpSize() })
                    .pointerInput(Unit) {
                        detectTapGestures { /* consume tap, prevent passthrough to Canvas */ }
                    }
                    .pointerInput(session, inputView) {
                        var accumDragX = 0f
                        var accumDragY = 0f
                        detectDragGestures(
                            onDragStart = {
                                // Read current handle position from live state
                                val ex = inputView.getPointX(selectionEndCol!! + 1).toFloat()
                                val ey = inputView.getPointY(selectionEndRow!!).toFloat()
                                accumDragX = ex
                                accumDragY = ey
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDragX += dragAmount.x
                                accumDragY += dragAmount.y

                                val curX = inputView.getCursorX(accumDragX)
                                val curY = inputView.getCursorY(accumDragY)

                                val content = session.terminalContent
                                if (content != null) {
                                    val scrollRows = content.activeRows - content.rows
                                    var newY2 = curY.coerceIn(-scrollRows, content.rows - 1)
                                    var newX2 = curX.coerceIn(0, content.columns - 1)

                                    val startRow = selectionStartRow!!
                                    val startCol = selectionStartCol!!
                                    if (newY2 < startRow) {
                                        newY2 = startRow
                                    }
                                    if (newY2 == startRow && newX2 < startCol) {
                                        newX2 = startCol
                                    }

                                    if (!content.isAlternateBufferActive) {
                                        var currentTop = inputView.getTopRow()
                                        if (newY2 <= currentTop) {
                                            currentTop = (currentTop - 1).coerceAtLeast(-scrollRows)
                                            inputView.setTopRow(currentTop)
                                        } else if (newY2 >= currentTop + content.rows) {
                                            currentTop = (currentTop + 1).coerceAtMost(0)
                                            inputView.setTopRow(currentTop)
                                        }
                                    }

                                    selectionEndRow = newY2
                                    selectionEndCol = getValidCurX(content, newY2, newX2)
                                }
                            }
                        )
                    }
            )

            // Action Mode Floating Popup Toolbar
            val renderer = inputView.mRenderer
            if (renderer != null) {
                val menuY = ((minOf(selectionStartRow!!, selectionEndRow!!) - topRow) * renderer.getFontLineSpacing()).coerceAtLeast(0)
                val menuX = (((selectionStartCol!! + selectionEndCol!!) / 2f) * renderer.getFontWidth()).coerceAtLeast(0f)

                Popup(
                    alignment = Alignment.TopCenter,
                    offset = IntOffset(menuX.toInt(), menuY),
                    onDismissRequest = {
                        selectionStartRow = null
                        selectionStartCol = null
                        selectionEndRow = null
                        selectionEndCol = null
                    }
                ) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = {
                                val content = session.terminalContent
                                if (content != null) {
                                    val text = content.getSelectedText(selectionStartCol!!, selectionStartRow!!, selectionEndCol!!, selectionEndRow!!)
                                    session.onCopyTextToClipboard(text)
                                }
                                selectionStartRow = null
                                selectionStartCol = null
                                selectionEndRow = null
                                selectionEndCol = null
                            }) {
                                Text("Copy")
                            }

                            TextButton(onClick = {
                                selectionStartRow = null
                                selectionStartCol = null
                                selectionEndRow = null
                                selectionEndCol = null
                                session.onPasteTextFromClipboard()
                            }) {
                                Text("Paste")
                            }

                            TextButton(onClick = {
                                val content = session.terminalContent
                                if (content != null) {
                                    inputView.showContextMenu()
                                }
                                selectionStartRow = null
                                selectionStartCol = null
                                selectionEndRow = null
                                selectionEndCol = null
                            }) {
                                Text("More")
                            }
                        }
                    }
                }
            }
        }
    }
}

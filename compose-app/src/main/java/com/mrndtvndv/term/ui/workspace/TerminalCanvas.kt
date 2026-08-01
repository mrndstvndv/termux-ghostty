package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalTextInputService
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextInputSession
import com.mrndtvndv.term.input.ImeEditCommandProcessor
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TerminalSession
import com.termux.terminal.TextStyle
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalConstants
import com.termux.terminal.GhosttyMouseEvent
import com.termux.view.TerminalRenderer
import java.io.File
import kotlin.math.sqrt

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

private fun sendDelete(
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController
) {
    val code = KeyHandler.getCode(
        KeyEvent.KEYCODE_DEL, 0,
        session.isCursorKeysApplicationMode,
        session.isKeypadApplicationMode
    )
    if (code != null) session.write(code)
    else inputCodePoint(127, false, false, session, extraKeysController)
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

private val selectionHandleVisualSize = 20.dp
private val selectionHandleTouchTargetSize = 48.dp

@Composable
private fun SelectionHandle(
    pointsLeft: Boolean,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    val handleColor = MaterialTheme.colorScheme.primary

    Canvas(
        modifier = modifier
            .size(selectionHandleTouchTargetSize)
            .semantics {
                this.contentDescription = contentDescription
            }
    ) {
        val visualSize = selectionHandleVisualSize.toPx()
        val radius = visualSize / 2f
        val visualLeft = if (pointsLeft) size.width - visualSize else 0f
        val rectangleLeft = if (pointsLeft) visualLeft + radius else visualLeft

        drawRect(
            color = handleColor,
            topLeft = Offset(rectangleLeft, 0f),
            size = androidx.compose.ui.geometry.Size(radius, radius)
        )
        drawCircle(
            color = handleColor,
            radius = radius,
            center = Offset(visualLeft + radius, radius)
        )
    }
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

    // Optional whole-frame shader overlay (CRT, glitch, matrix rain, ...) — see TerminalEffects.
    // The frame is rendered into an offscreen bitmap and pushed through the shader via
    // setInputShader, which avoids RenderEffect/GPU-cache timing quirks on some devices.
    val terminalEffect = TerminalEffect.fromPref(sharedPreferences.getString("terminal_effect", "none"))
    val postShader = rememberRuntimeShader(terminalEffect)
    var shaderTime by remember { mutableFloatStateOf(0f) }

    // Cursor trail — warp smear ported from ghostty's cursor_warp.glsl shader
    val cursorTrailEnabled = sharedPreferences.getBoolean("cursor_trail", false)
    val cursorWarpState = remember { CursorWarpState() }

    var frameTrigger by remember { mutableStateOf(0) }
    // Bumped only when the terminal content actually changed (view invalidation or a new
    // ghostty frame). The 60fps animation loop bumps frameTrigger alone, so the offscreen
    // bitmap is only re-rendered/re-uploaded when the content is dirty — re-rendering and
    // re-uploading it every frame caused torn/black frames on the GPU.
    var contentDirty by remember { mutableIntStateOf(0) }
    val bitmapRenderState = remember { BitmapRenderState() }

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
            contentDirty++
            frameTrigger++
        }
    }

    LaunchedEffect(currentFontSize.value) {
        inputView.setTextSize(currentFontSize.value)
    }

    // Read the clock from the Canvas draw scope so animation invalidates drawing only. Updating
    // frameTrigger here would recompose the entire terminal hierarchy once per display frame.
    LaunchedEffect(terminalEffect, postShader, cursorTrailEnabled) {
        if ((terminalEffect.animated && postShader != null) || cursorTrailEnabled) {
            while (true) {
                withFrameNanos { nanos ->
                    shaderTime = nanos / 1_000_000_000f
                }
            }
        }
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
    var showToolbar by remember { mutableStateOf(false) }

    val isSelectingText = remember {
        derivedStateOf {
            selectionStartCol != null && selectionStartRow != null && selectionEndCol != null && selectionEndRow != null
        }
    }

    var combiningAccent by remember { mutableIntStateOf(0) }

    @Suppress("DEPRECATION") // PlatformTextInputService + TextInputSession still needed for terminal IME
    val textInputService = LocalTextInputService.current
    @Suppress("DEPRECATION")
    var imeSession by remember { mutableStateOf<TextInputSession?>(null) }

    // Handle native Android key events (shared between hardware keyboard and IME SendKeyEventCommand)
    fun handleNativeKeyEvent(nativeEvent: android.view.KeyEvent): Boolean {
        if (nativeEvent.action != KeyEvent.ACTION_DOWN) return false

        val keyCode = nativeEvent.keyCode
        // Don't consume the back key — let system navigation handle it.
        // KeyHandler.getCode(KEYCODE_BACK) would map it to ESC, stealing back navigation.
        if (keyCode == KeyEvent.KEYCODE_BACK) return false

        if (isSelectingText.value) {
            selectionStartCol = null
            selectionStartRow = null
            selectionEndCol = null
            selectionEndRow = null
            showToolbar = false
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
            keyCode, keyMod,
            session.isCursorKeysApplicationMode,
            session.isKeypadApplicationMode
        )
        if (code != null) {
            session.write(code)
            return true
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
            return true
        }
        return false
    }

    // IME edit-command handling — the state machine lives in ImeEditCommandProcessor so it
    // stays unit-testable and survives a terminal backend swap; this adapter maps its
    // semantic ops onto the Termux session.
    val imeProcessor = remember(session, extraKeysController) {
        ImeEditCommandProcessor(SessionTerminalInput(session, extraKeysController))
    }

    // Clean up the IME session when this composable leaves composition
    DisposableEffect(Unit) {
        onDispose {
            imeSession?.dispose()
            imeSession = null
        }
    }

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
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { keyEvent ->
                handleNativeKeyEvent(keyEvent.nativeKeyEvent)
            }
    ) {

        // Rendering Layer (Compose Canvas)
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics {
                    text = AnnotatedString(visibleText)
                }
                .pointerInput(inputView, session) {
                    awaitEachGesture {
                        var scaleAccumulator = 1f
                        var dragAccumulator = 0f
                        var gestureStartY = 0f
                        var initialScrollSet = false
                        var isVerticalScroll = false
                        var isHorizontalSwipe = false
                        var isPinchZoom = false
                        var totalPanX = 0f
                        var totalPanY = 0f
                        val touchSlop = viewConfiguration.touchSlop

                        awaitFirstDown(requireUnconsumed = false)
                        if (isSelectingText.value) return@awaitEachGesture

                        do {
                            val event = awaitPointerEvent()
                            val canceled = event.changes.any { it.isConsumed }
                            if (!canceled) {
                                val zoomChange = event.calculateZoom()
                                val panChange = event.calculatePan()
                                val pointerCount = event.changes.size

                                if (pointerCount > 1 || zoomChange != 1f) {
                                    isPinchZoom = true
                                }

                                if (isPinchZoom) {
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    val renderer = inputView.mRenderer
                                    if (renderer != null && zoomChange != 1f) {
                                        scaleAccumulator *= zoomChange
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
                                } else if (!isHorizontalSwipe) {
                                    totalPanX += panChange.x
                                    totalPanY += panChange.y

                                    val dist = kotlin.math.sqrt(totalPanX * totalPanX + totalPanY * totalPanY)

                                    if (!isVerticalScroll && dist > touchSlop) {
                                        if (kotlin.math.abs(totalPanX) > 1.5f * kotlin.math.abs(totalPanY)) {
                                            isHorizontalSwipe = true
                                        } else {
                                            isVerticalScroll = true
                                        }
                                    }

                                    if (isVerticalScroll && panChange.y != 0f) {
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }

                                        val renderer = inputView.mRenderer
                                        if (renderer != null) {
                                            val centroid = event.calculateCentroid()
                                            if (!initialScrollSet) {
                                                initialScrollSet = true
                                                gestureStartY = centroid.y

                                                if (!session.isMouseTrackingActive() &&
                                                    !session.isAlternateBufferActive() &&
                                                    session.hasActiveTerminalBackend()
                                                ) {
                                                    val viewHeight = inputView.height.toFloat()
                                                    if (viewHeight > 0f) {
                                                        val transcriptRows = session.getActiveTranscriptRows()
                                                        if (transcriptRows > 0) {
                                                            val ratio = (gestureStartY / viewHeight).coerceIn(0f, 1f)
                                                            val targetTopRow = -(transcriptRows * ratio).toInt()
                                                                .coerceIn(-transcriptRows, 0)
                                                            inputView.setTopRow(targetTopRow)
                                                            session.setGhosttyTopRow(targetTopRow)
                                                            inputView.invalidate()
                                                        }
                                                    }
                                                }
                                            }

                                            dragAccumulator += panChange.y
                                            val fontHeight = renderer.getFontLineSpacing()
                                            val deltaRows = (dragAccumulator / fontHeight).toInt()
                                            if (deltaRows != 0) {
                                                dragAccumulator -= deltaRows * fontHeight
                                                val time = android.os.SystemClock.uptimeMillis()
                                                val me = MotionEvent.obtain(
                                                    time, time,
                                                    MotionEvent.ACTION_MOVE,
                                                    centroid.x, centroid.y, 0
                                                )
                                                inputView.doScroll(me, -deltaRows)
                                                me.recycle()
                                            }
                                        }
                                    }
                                }
                            }
                        } while (!canceled && event.changes.any { it.pressed })
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
                                showToolbar = false
                            } else {
                                // Forward tap as mouse click when terminal mouse tracking is active
                                sendTouchAsMouseClick(session, inputView, offset.x, offset.y)

                                val unconditionalKeyboard =
                                    sharedPreferences.getBoolean("unconditional_soft_keyboard_on_tap", true)
                                if (unconditionalKeyboard || !session.isMouseTrackingActive()) {
                                    focusRequester.requestFocus()
                                    if (imeSession == null || imeSession?.isOpen != true) {
                                        imeSession?.dispose()
                                        imeSession = textInputService?.startInput(
                                            value = TextFieldValue(""),
                                            imeOptions = ImeOptions(
                                                keyboardType = KeyboardType.Ascii,
                                                imeAction = ImeAction.None,
                                                autoCorrect = false
                                            ),
                                            onEditCommand = imeProcessor::process,
                                            onImeActionPerformed = { }
                                        )
                                    }
                                    imeSession?.showSoftwareKeyboard()
                                    keyboardController?.show()
                                }

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
                                    showToolbar = true
                                }
                            }
                        }
                    )
                }
        ) {
            val renderer = inputView.mRenderer ?: return@Canvas
            val renderCache = inputView.renderFrameCache ?: return@Canvas

            @Suppress("UNUSED_VARIABLE") // trigger forces Canvas redraw by reading frameTrigger state inside draw scope
            val trigger = frameTrigger
            // This state read is draw-scoped. It keeps cursor trails animating without making the
            // animation clock a composition input.
            val animationTimeSeconds = shaderTime

            val currentSnapshot = renderCache.getSnapshotForRender(
                session.isGhosttyCursorBlinkingEnabled,
                session.ghosttyCursorBlinkState
            ) ?: return@Canvas

            val startY = selectionStartRow ?: -1
            val endY = selectionEndRow ?: -1
            val startX = selectionStartCol ?: -1
            val endX = selectionEndCol ?: -1

            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                if (postShader != null) {
                    // Render content only when it changes. The animation redraws the shader over
                    // a stable bitmap; alternating buffers prevents the GPU from sampling a bitmap
                    // while the UI thread is writing the next terminal frame into it.
                    val targetW = size.width.toInt().coerceAtLeast(1)
                    val targetH = size.height.toInt().coerceAtLeast(1)
                    bitmapRenderState.renderIfNeeded(
                        width = targetW,
                        height = targetH,
                        contentVersion = contentDirty,
                        selection = RenderSelection(startY, endY, startX, endX)
                    ) { bitmapCanvas ->
                        renderer.render(currentSnapshot, bitmapCanvas, startY, endY, startX, endX)
                    }
                    bitmapRenderState.draw(
                        canvas = native,
                        shader = postShader,
                        timeSeconds = animationTimeSeconds,
                        width = size.width,
                        height = size.height
                    )
                } else {
                    renderer.render(currentSnapshot, native, startY, endY, startX, endX)
                }
            }
            if (postShader == null && terminalEffect != TerminalEffect.NONE) {
                drawLegacyOverlay(terminalEffect)
            }
            if (cursorTrailEnabled) {
                drawCursorTrail(
                    snapshot = currentSnapshot,
                    state = cursorWarpState,
                    renderer = renderer,
                    topRow = inputView.getTopRow()
                )
            }
        }

        // Selection Handles and Action Toolbar Overlay
        if (isSelectingText.value) {
            val startCol = selectionStartCol!!
            val startRow = selectionStartRow!!
            val endCol = selectionEndCol!!
            val endRow = selectionEndRow!!

            // Handles are rendered one row below the selection so they don't obscure the text
            val selectionHandleRowOffset = 1

            val topRow = inputView.getTopRow()
            val fontLineSpacing = inputView.mRenderer?.getFontLineSpacing()?.toFloat() ?: 0f

            val startX = inputView.getPointX(startCol).toFloat()
            val startY = inputView.getPointY(startRow + selectionHandleRowOffset).toFloat()

            val endX = inputView.getPointX(endCol + 1).toFloat()
            val endY = inputView.getPointY(endRow + selectionHandleRowOffset).toFloat()

            val density = LocalDensity.current
            val handleVisualSizePx = with(density) { selectionHandleVisualSize.toPx() }
            val handleTouchTargetSizePx = with(density) { selectionHandleTouchTargetSize.toPx() }

            val viewWidth = inputView.width.toFloat()
            val leftHandlePointsLeft = startX - handleVisualSizePx >= 0f
            val leftHandleVisualX = if (leftHandlePointsLeft) {
                startX - handleVisualSizePx
            } else {
                startX
            }
            val leftHandleX = if (leftHandlePointsLeft) {
                leftHandleVisualX - (handleTouchTargetSizePx - handleVisualSizePx)
            } else {
                leftHandleVisualX
            }
            val leftHandleY = startY

            val rightHandlePointsLeft = endX + handleVisualSizePx > viewWidth
            val rightHandleVisualX = if (rightHandlePointsLeft) {
                endX - handleVisualSizePx
            } else {
                endX
            }
            val rightHandleX = if (rightHandlePointsLeft) {
                rightHandleVisualX - (handleTouchTargetSizePx - handleVisualSizePx)
            } else {
                rightHandleVisualX
            }
            val rightHandleY = endY

            // Left Selection Handle
            SelectionHandle(
                pointsLeft = leftHandlePointsLeft,
                contentDescription = "Start Selection Handle",
                modifier = Modifier
                    .offset { IntOffset(leftHandleX.toInt(), leftHandleY.toInt()) }
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
                                val sy = inputView.getPointY(selectionStartRow!! + selectionHandleRowOffset).toFloat()
                                accumDragX = sx
                                accumDragY = sy
                            },
                            onDragEnd = {
                                showToolbar = true
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDragX += dragAmount.x
                                accumDragY += dragAmount.y

                                val curX = inputView.getCursorX(accumDragX)
                                val curY = inputView.getCursorY(accumDragY - fontLineSpacing * 0.5f)

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
            SelectionHandle(
                pointsLeft = rightHandlePointsLeft,
                contentDescription = "End Selection Handle",
                modifier = Modifier
                    .offset { IntOffset(rightHandleX.toInt(), rightHandleY.toInt()) }
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
                                val ey = inputView.getPointY(selectionEndRow!! + selectionHandleRowOffset).toFloat()
                                accumDragX = ex
                                accumDragY = ey
                            },
                            onDragEnd = {
                                showToolbar = true
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                accumDragX += dragAmount.x
                                accumDragY += dragAmount.y

                                val curX = inputView.getCursorX(accumDragX)
                                val curY = inputView.getCursorY(accumDragY - fontLineSpacing * 0.5f)

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
            if (renderer != null && showToolbar) {
                val selStartRow = minOf(selectionStartRow!!, selectionEndRow!!)
                val selEndRow = maxOf(selectionStartRow!!, selectionEndRow!!)
                val selStartCol = minOf(selectionStartCol!!, selectionEndCol!!)
                val selEndCol = maxOf(selectionStartCol!!, selectionEndCol!!)

                val fontLineSpacing = renderer.getFontLineSpacing()
                val fontWidth = renderer.getFontWidth()

                // Two contiguous text buttons inside a compact pill-shaped toolbar.
                val toolbarWidth = with(density) { 176.dp.toPx() }
                val toolbarHeight = with(density) { 48.dp.toPx() }

                // Selection bounds in viewport pixel space
                val selTopPx = (selStartRow - topRow) * fontLineSpacing
                val selBottomPx = (selEndRow - topRow + 1) * fontLineSpacing
                val viewHeight = inputView.height.toFloat()
                val viewWidth = inputView.width.toFloat()

                val toolbarGap = with(density) { 4.dp.toPx() }
                val handlesBottomPx = selBottomPx + with(density) {
                    selectionHandleVisualSize.toPx()
                }
                val menuBelowSelectionY = handlesBottomPx + toolbarGap

                // Prefer above the selection. For selections near the top, place the toolbar
                // below the handles instead of directly below the selected text.
                val menuY = when {
                    selTopPx >= toolbarHeight + toolbarGap -> {
                        (selTopPx - toolbarHeight - toolbarGap).toInt()
                    }
                    menuBelowSelectionY + toolbarHeight <= viewHeight -> {
                        menuBelowSelectionY.toInt()
                    }
                    else -> {
                        // Keep the toolbar on-screen when neither side has enough room.
                        (viewHeight - toolbarHeight).coerceAtLeast(0f).toInt()
                    }
                }

                // Center horizontally on selection midpoint, clamped to viewport edges
                val selMidX = ((selStartCol + selEndCol) / 2f) * fontWidth
                val maxMenuX = (viewWidth - toolbarWidth).coerceAtLeast(0f).toInt()
                val menuX = (selMidX - toolbarWidth / 2f).toInt().coerceIn(0, maxMenuX)

                Popup(
                    alignment = Alignment.TopStart,
                    offset = IntOffset(menuX, menuY),
                    onDismissRequest = {
                        showToolbar = false
                    }
                ) {
                    val toolbarShape = RoundedCornerShape(percent = 50)

                    Surface(
                        shape = toolbarShape,
                        tonalElevation = 8.dp,
                        shadowElevation = 8.dp,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(176.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .clip(toolbarShape)
                        ) {
                            TextButton(
                                onClick = {
                                    val content = session.terminalContent
                                    if (content != null) {
                                        val text = content.getSelectedText(
                                            selectionStartCol!!,
                                            selectionStartRow!!,
                                            selectionEndCol!!,
                                            selectionEndRow!!
                                        )
                                        session.onCopyTextToClipboard(text)
                                    }
                                    selectionStartRow = null
                                    selectionStartCol = null
                                    selectionEndRow = null
                                    selectionEndCol = null
                                    showToolbar = false
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Copy")
                            }

                            TextButton(
                                onClick = {
                                    selectionStartRow = null
                                    selectionStartCol = null
                                    selectionEndRow = null
                                    selectionEndCol = null
                                    showToolbar = false
                                    session.onPasteTextFromClipboard()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                                shape = RoundedCornerShape(0.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                            ) {
                                Text("Paste")
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Adapter from the IME's semantic terminal ops to a Termux session. Swapping the
 * terminal backend (e.g. a Rust emulator) only requires a new TerminalInput
 * implementation — the IME state machine itself is untouched.
 */
private class SessionTerminalInput(
    private val session: TerminalSession,
    private val extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController
) : ImeEditCommandProcessor.TerminalInput {

    override fun inputCodePoint(codePoint: Int) {
        inputCodePoint(
            codePoint,
            ctrlHeldFromEvent = false,
            altHeldFromEvent = false,
            session = session,
            extraKeysController = extraKeysController
        )
    }

    override fun delete() = sendDelete(session, extraKeysController)

    override fun moveCursor(delta: Int) {
        val keyCode = if (delta < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(Math.abs(delta)) {
            val code = KeyHandler.getCode(
                keyCode, 0,
                session.isCursorKeysApplicationMode,
                session.isKeypadApplicationMode
            )
            if (code != null) session.write(code)
        }
    }
}

/**
 * Cursor positions for the warp trail — previous/current cursor cell and the time of the last move.
 */
private class CursorWarpState {
    var prevCol = -1
    var prevRow = -1
    var currCol = -1
    var currRow = -1
    var changeNanos = 0L
}

private data class RenderSelection(
    val y1: Int,
    val y2: Int,
    val x1: Int,
    val x2: Int
)

/**
 * Owns the software-rendered shader inputs. Content changes alternate between two bitmaps so a
 * hardware canvas never samples the same bitmap that the UI thread is currently repainting.
 */
private class BitmapRenderState {
    private data class Buffer(
        val bitmap: Bitmap,
        val shader: BitmapShader
    )

    private val shaderPaint = AndroidPaint()
    private var buffers: Array<Buffer> = emptyArray()
    private var activeBufferIndex = -1
    private var renderedContentVersion = Int.MIN_VALUE
    private var renderedSelectionY1 = Int.MIN_VALUE
    private var renderedSelectionY2 = Int.MIN_VALUE
    private var renderedSelectionX1 = Int.MIN_VALUE
    private var renderedSelectionX2 = Int.MIN_VALUE

    fun renderIfNeeded(
        width: Int,
        height: Int,
        contentVersion: Int,
        selection: RenderSelection,
        render: (AndroidCanvas) -> Unit
    ) {
        ensureSize(width, height)
        if (!needsRender(contentVersion, selection)) return

        val nextBufferIndex = (activeBufferIndex + 1) % buffers.size
        render(AndroidCanvas(buffers[nextBufferIndex].bitmap))
        activeBufferIndex = nextBufferIndex
        renderedContentVersion = contentVersion
        renderedSelectionY1 = selection.y1
        renderedSelectionY2 = selection.y2
        renderedSelectionX1 = selection.x1
        renderedSelectionX2 = selection.x2
    }

    fun draw(
        canvas: AndroidCanvas,
        shader: RuntimeShader,
        timeSeconds: Float,
        width: Float,
        height: Float
    ) {
        shader.updateUniforms(timeSeconds, width, height)
        shader.setInputShader("content", buffers[activeBufferIndex].shader)
        shaderPaint.shader = shader
        canvas.drawRect(0f, 0f, width, height, shaderPaint)
    }

    private fun ensureSize(width: Int, height: Int) {
        if (buffers.size == 2 && buffers[0].bitmap.width == width && buffers[0].bitmap.height == height) return

        buffers = Array(2) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Buffer(
                bitmap = bitmap,
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
        }
        activeBufferIndex = -1
        renderedContentVersion = Int.MIN_VALUE
        renderedSelectionY1 = Int.MIN_VALUE
        renderedSelectionY2 = Int.MIN_VALUE
        renderedSelectionX1 = Int.MIN_VALUE
        renderedSelectionX2 = Int.MIN_VALUE
    }

    private fun needsRender(contentVersion: Int, selection: RenderSelection): Boolean =
        activeBufferIndex == -1 ||
            contentVersion != renderedContentVersion ||
            selection.y1 != renderedSelectionY1 ||
            selection.y2 != renderedSelectionY2 ||
            selection.x1 != renderedSelectionX1 ||
            selection.x2 != renderedSelectionX2
}

// Cursor warp trail — port of ghostty's cursor_warp.glsl shader
private const val WarpDurationSeconds = 0.2f
private const val WarpTrailSize = 0.8f
private const val WarpMinDistanceCells = 1.5f
private const val WarpThicknessY = 1.0f
private const val WarpThicknessX = 0.9f

/** EaseOutCirc, matching the shader's default easing. */
private fun easeOutCirc(x: Float): Float = sqrt(1f - (x - 1f) * (x - 1f))

/** Warp quad corners plus the current cursor rect (for the punch-out hole). */
private class WarpQuad(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset,
    val cursorRect: Rect
)

/**
 * Computes the four eased corners of the warp smear between the previous and
 * current cursor rects, plus the current cursor rect for the punch-out hole.
 */
private fun warpQuad(
    state: CursorWarpState,
    topRow: Int,
    fontWidth: Float,
    lineHeight: Float,
    progressSeconds: Float
): WarpQuad {
    // Thickness-scaled rect corners (TRAIL_THICKNESS = 1.0, TRAIL_THICKNESS_X = 0.9)
    fun rectCorners(x: Float, y: Float): List<Offset> {
        val halfWidth = fontWidth * 0.5f * WarpThicknessX
        val halfHeight = lineHeight * 0.5f * WarpThicknessY
        val centerX = x + fontWidth * 0.5f
        val centerY = y + lineHeight * 0.5f
        return listOf(
            Offset(centerX - halfWidth, centerY - halfHeight), // top-left
            Offset(centerX + halfWidth, centerY - halfHeight), // top-right
            Offset(centerX + halfWidth, centerY + halfHeight), // bottom-right
            Offset(centerX - halfWidth, centerY + halfHeight) // bottom-left
        )
    }

    val ccRect = rectCorners(state.currCol * fontWidth, (state.currRow - topRow) * lineHeight)
    val cpRect = rectCorners(state.prevCol * fontWidth, (state.prevRow - topRow) * lineHeight)

    // Per-corner durations from alignment with the move direction
    val signX = if (state.currCol >= state.prevCol) 1f else -1f
    val signY = if (state.currRow >= state.prevRow) 1f else -1f
    val leadDuration = WarpDurationSeconds * (1f - WarpTrailSize)
    val sideDuration = (leadDuration + WarpDurationSeconds) / 2f

    fun durationFromDot(dotValue: Float): Float = when {
        dotValue >= 0.5f -> leadDuration
        dotValue >= -0.5f -> sideDuration
        else -> WarpDurationSeconds
    }

    var tlDuration = durationFromDot(-signX + signY)
    var trDuration = durationFromDot(signX + signY)
    var blDuration = durationFromDot(-signX - signY)
    var brDuration = durationFromDot(signX - signY)

    // Horizontal-rail correction so leading/trailing edges move as rails
    val isMovingRight = signX >= 0.5f
    val isMovingLeft = -signX >= 0.5f
    val leftRailDuration = durationFromDot(-signX)
    val rightRailDuration = durationFromDot(signX)
    if (isMovingLeft) {
        tlDuration = leftRailDuration
        blDuration = leftRailDuration
    }
    if (isMovingRight) {
        trDuration = rightRailDuration
        brDuration = rightRailDuration
    }

    fun easedCorner(progress: Float, from: Offset, to: Offset): Offset {
        val t = easeOutCirc(progress.coerceIn(0f, 1f))
        return Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    val cursorX = state.currCol * fontWidth
    val cursorY = (state.currRow - topRow) * lineHeight
    return WarpQuad(
        topLeft = easedCorner(progressSeconds / tlDuration, cpRect[0], ccRect[0]),
        topRight = easedCorner(progressSeconds / trDuration, cpRect[1], ccRect[1]),
        bottomRight = easedCorner(progressSeconds / brDuration, cpRect[2], ccRect[2]),
        bottomLeft = easedCorner(progressSeconds / blDuration, cpRect[3], ccRect[3]),
        cursorRect = Rect(cursorX, cursorY, cursorX + fontWidth, cursorY + lineHeight)
    )
}

/**
 * Draws the warp smear between the previous and current cursor positions.
 * Leading corners ease in fast, trailing corners lag, producing the warp/smear shape.
 */
private fun DrawScope.drawCursorTrail(
    snapshot: ScreenSnapshot,
    state: CursorWarpState,
    renderer: TerminalRenderer,
    topRow: Int
) {
    if (snapshot.isCursorVisible) {
        val col = snapshot.getCursorCol()
        val row = snapshot.getCursorRow()
        if (state.currCol != col || state.currRow != row) {
            state.prevCol = state.currCol
            state.prevRow = state.currRow
            state.currCol = col
            state.currRow = row
            state.changeNanos = System.nanoTime()
        }
    }

    val now = System.nanoTime()
    if (state.currCol < 0 || state.prevCol < 0) return

    // No trail for tiny moves (threshold in cursor-height units, like the shader)
    val moveCols = (state.currCol - state.prevCol).toFloat()
    val moveRows = (state.currRow - state.prevRow).toFloat()
    if (sqrt(moveCols * moveCols + moveRows * moveRows) < WarpMinDistanceCells) return

    val progressSeconds = (now - state.changeNanos) / 1_000_000_000f
    if (progressSeconds >= WarpDurationSeconds - 0.001f) return

    val quad = warpQuad(
        state = state,
        topRow = topRow,
        fontWidth = renderer.getFontWidth(),
        lineHeight = renderer.getFontLineSpacing().toFloat(),
        progressSeconds = progressSeconds
    )

    var cursorColor = Color(snapshot.getPaletteColor(TextStyle.COLOR_INDEX_CURSOR))
    // Boost dark cursor colors so the trail reads on dark backgrounds
    val luminance = 0.299f * cursorColor.red + 0.587f * cursorColor.green + 0.114f * cursorColor.blue
    if (luminance < 0.45f) {
        cursorColor = lerp(cursorColor, Color.White, 0.5f)
    }

    // Warp quad with an even-odd hole where the current cursor sits, so the
    // rendered block cursor stays visible on top (mirrors the shader's punch-out)
    val quadPath = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(quad.topLeft.x, quad.topLeft.y)
        lineTo(quad.topRight.x, quad.topRight.y)
        lineTo(quad.bottomRight.x, quad.bottomRight.y)
        lineTo(quad.bottomLeft.x, quad.bottomLeft.y)
        close()
        addRect(quad.cursorRect)
    }
    drawPath(quadPath, cursorColor)
}

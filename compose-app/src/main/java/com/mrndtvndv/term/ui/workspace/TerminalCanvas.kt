package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import java.io.File
import android.graphics.Typeface
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import android.content.Context

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController,
    onOpenUrl: (String) -> Unit,
    onViewCreated: (com.termux.view.TerminalView) -> Unit,
    onViewReleased: (com.termux.view.TerminalView) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputView by remember { mutableStateOf<ComposeInputTerminalView?>(null) }
    var frameTrigger by remember { mutableStateOf(0) }
    val selectionRange = remember { IntArray(4) { -1 } }

    Box(modifier = modifier.fillMaxSize()) {
        // Bottom: Rendering Layer (Compose)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentInputView = inputView ?: return@Canvas
            val renderer = currentInputView.mRenderer ?: return@Canvas
            val renderCache = currentInputView.renderFrameCache ?: return@Canvas

            @Suppress("UNUSED_VARIABLE")
            val trigger = frameTrigger

            // Read from synchronous RenderFrameCache instead of volatile transport snapshots
            val currentSnapshot = renderCache.getSnapshotForRender(
                session.isGhosttyCursorBlinkingEnabled,
                session.ghosttyCursorBlinkState
            ) ?: return@Canvas

            currentInputView.getSelectors(selectionRange)

            drawIntoCanvas { canvas ->
                renderer.render(
                    currentSnapshot,
                    canvas.nativeCanvas,
                    selectionRange[0], selectionRange[1],
                    selectionRange[2], selectionRange[3]
                )
            }
        }

        // Top: Interaction Layer (Transparent View)
        AndroidView(
            factory = { context ->
                val sharedPreferences = context.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
                val sizes = TermuxAppSharedPreferences.getDefaultFontSizes(context)
                val currentFontSize = sharedPreferences.getInt("font_size", sizes[0])

                ComposeInputTerminalView(context).apply {
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setTextSize(currentFontSize)

                    // Load custom font
                    val customFontFile = File(context.filesDir, "font.ttf")
                    val face = if (customFontFile.exists() && customFontFile.length() > 0) {
                        try { Typeface.createFromFile(customFontFile) } catch (e: Exception) { Typeface.MONOSPACE }
                    } else { Typeface.MONOSPACE }
                    setTypeface(face)

                    setTerminalViewClient(object : com.termux.shared.termux.terminal.TermuxTerminalViewClientBase() {
                        override fun onSingleTapUp(e: android.view.MotionEvent) {
                            val url = getTerminalTranscriptUrlOnTap(e)
                            if (url != null) {
                                onOpenUrl(url)
                                return
                            }
                            this@apply.requestFocus()
                            com.termux.shared.view.KeyboardUtils.showSoftKeyboard(context, this@apply)
                        }
                        override fun shouldOpenTerminalTranscriptURLOnClick() = true
                        override fun getTerminalTranscriptUrlOnTap(e: android.view.MotionEvent) = getVisibleLinkHit(e)?.url
                        override fun onScale(scale: Float): Float {
                            val ret = super.onScale(scale)
                            sharedPreferences.edit().putInt("font_size", mRenderer.mTextSize).apply()
                            return ret
                        }
                        override fun readControlKey(): Boolean {
                            return extraKeysController.readControl()
                        }
                        override fun readAltKey(): Boolean {
                            return extraKeysController.readAlt()
                        }
                        override fun readShiftKey(): Boolean {
                            return extraKeysController.readShift()
                        }
                        override fun readFnKey(): Boolean {
                            return extraKeysController.readFn()
                        }
                    })

                    attachSession(session)
                    onInvalidateCallback = {
                        frameTrigger++
                    }
                    inputView = this
                    onViewCreated(this)
                }
            },
            update = { view ->
                if (view.mTermSession != session) {
                    view.attachSession(session)
                    onViewCreated(view)
                }
            },
            onRelease = { view ->
                view.detachSession()
                onViewReleased(view)
                inputView = null
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

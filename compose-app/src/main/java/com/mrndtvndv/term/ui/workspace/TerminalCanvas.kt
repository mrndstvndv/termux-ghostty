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
import kotlinx.coroutines.delay
import java.io.File
import android.graphics.Typeface
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import android.content.Context

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    onOpenUrl: (String) -> Unit,
    onViewCreated: (com.termux.view.TerminalView) -> Unit,
    onViewReleased: (com.termux.view.TerminalView) -> Unit,
    modifier: Modifier = Modifier
) {
    var inputView by remember { mutableStateOf<ComposeInputTerminalView?>(null) }
    var snapshot by remember { mutableStateOf(session.ghosttyPublishedFrameDelta?.transportSnapshot) }
    val selectionRange = remember { IntArray(4) { -1 } }
    var selectionTrigger by remember { mutableStateOf(0) }

    // Poll snapshot and selection updates
    LaunchedEffect(session, inputView) {
        while (true) {
            val delta = session.ghosttyPublishedFrameDelta
            val newSnapshot = delta?.transportSnapshot
            if (newSnapshot != snapshot) {
                snapshot = newSnapshot
            }
            inputView?.let { view ->
                val tempSel = IntArray(4)
                view.getSelectors(tempSel)
                if (!tempSel.contentEquals(selectionRange)) {
                    tempSel.copyInto(selectionRange)
                    selectionTrigger++ // Trigger recomposition for selection highlights
                }
            }
            delay(16)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // Bottom: Rendering Layer (Compose)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val currentSnapshot = snapshot ?: return@Canvas
            val currentInputView = inputView ?: return@Canvas
            val renderer = currentInputView.mRenderer ?: return@Canvas

            // Read selectionTrigger so it forces recomposition when selection changes
            @Suppress("UNUSED_VARIABLE")
            val trigger = selectionTrigger

            drawIntoCanvas { canvas ->
                // Draw everything via TerminalRenderer using Compose nativeCanvas
                // This includes correct colors, fonts, styles, and selection highlights
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
                            // Update font size preference on scale
                            sharedPreferences.edit().putInt("font_size", mRenderer.mTextSize).apply()
                            return ret
                        }
                    })

                    attachSession(session)
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

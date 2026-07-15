package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences


import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import com.termux.shared.view.KeyboardUtils

fun TerminalView.detachSession() {
    attachSession(null)
}

/**
 * Force the terminal session to resize to match the actual view dimensions.
 * This bypasses TerminalView.updateSize()'s getWindowVisibility() guard
 * which blocks resizing when hosted inside Compose's AndroidView.
 */
private fun TerminalView.forceUpdateSize() {
    val viewWidth = width
    val viewHeight = height
    val renderer = mRenderer ?: return
    val session = mTermSession ?: return
    if (viewWidth == 0 || viewHeight == 0) return

    val fontWidth = renderer.getFontWidth()
    val fontLineSpacing = renderer.getFontLineSpacing()
    if (fontWidth <= 0 || fontLineSpacing <= 0) return

    val newColumns = Math.max(4, (viewWidth / fontWidth).toInt())
    // Approximate row count: use full view height / line spacing.
    // The original uses (viewHeight - mFontLineSpacingAndAscent) / mFontLineSpacing
    // which accounts for a single ascent offset. We approximate by subtracting one line spacing.
    val newRows = Math.max(4, (viewHeight - fontLineSpacing) / fontLineSpacing)

    if (session.columns == newColumns && session.rows == newRows) return

    android.util.Log.i("TerminalWorkspace", "forceUpdateSize: ${newColumns}x${newRows} (view: ${viewWidth}x${viewHeight})")
    val cellWidth = Math.max(1, Math.round(fontWidth))
    val cellHeight = Math.max(1, fontLineSpacing)
    session.updateSize(newColumns, newRows, cellWidth, cellHeight)
    scrollTo(0, 0)
    invalidate()
}

@Composable
fun TerminalWorkspaceContainer(
    session: TerminalSession,
    extraKeysController: com.mrndtvndv.term.ui.keyboard.ExtraKeysController,
    onViewCreated: (TerminalView) -> Unit,
    onViewReleased: () -> Unit,
    modifier: Modifier = Modifier
) {
    AndroidView(
        factory = { context ->
            val sizes = TermuxAppSharedPreferences.getDefaultFontSizes(context)
            val defaultFontSize = sizes[0]
            val minFontSize = sizes[1]
            val maxFontSize = sizes[2]
            var currentFontSize = defaultFontSize
            TerminalView(context, null).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                isFocusable = true
                isFocusableInTouchMode = true
                setTextSize(currentFontSize)
                setTerminalViewClient(object : TermuxTerminalViewClientBase() {
                    override fun onSingleTapUp(e: MotionEvent) {
                        this@apply.requestFocus()
                        KeyboardUtils.showSoftKeyboard(context, this@apply)
                    }

                    override fun onScale(scale: Float): Float {
                        if (scale < 0.9f || scale > 1.1f) {
                            val increase = scale > 1.0f
                            val delta = if (increase) 2 else -2
                            val newSize = (currentFontSize + delta).coerceIn(minFontSize, maxFontSize)
                            if (newSize != currentFontSize) {
                                currentFontSize = newSize
                                this@apply.setTextSize(newSize)
                            }
                            return 1.0f
                        }
                        return scale
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

                // Use OnGlobalLayoutListener to resize once the view has real dimensions.
                // We call forceUpdateSize() which bypasses the getWindowVisibility() guard
                // that blocks TerminalView.updateSize() inside Compose's AndroidView.
                viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (width > 0 && height > 0) {
                            viewTreeObserver.removeOnGlobalLayoutListener(this)
                            forceUpdateSize()
                        }
                    }
                })

                onViewCreated(this)
            }
        },
        update = { view ->
            if (view.mTermSession != session) {
                view.attachSession(session)
            }
            onViewCreated(view)
        },
        onRelease = { view ->
            view.detachSession()
            onViewReleased()
        },
        modifier = modifier.fillMaxSize()
    )
}

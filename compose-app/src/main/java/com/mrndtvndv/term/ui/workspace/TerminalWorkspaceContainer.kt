package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences


import android.content.Context
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
    updateSize(true)
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
            val sharedPreferences = context.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
            val sizes = TermuxAppSharedPreferences.getDefaultFontSizes(context)
            val defaultFontSize = sizes[0]
            val minFontSize = sizes[1]
            val maxFontSize = sizes[2]
            var currentFontSize = sharedPreferences.getInt("font_size", defaultFontSize).coerceIn(minFontSize, maxFontSize)
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
                                sharedPreferences.edit().putInt("font_size", newSize).apply()
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

                // Force a resize once the view is actually laid out with real dimensions,
                // and every time the keyboard toggles, split-screen is resized, or orientation changes.
                // We call forceUpdateSize() which bypasses the getWindowVisibility() guard
                // that blocks TerminalView.updateSize() inside Compose's AndroidView.
                addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int
                    ) {
                        val w = right - left
                        val h = bottom - top
                        if (w > 0 && h > 0 && (w != (oldRight - oldLeft) || h != (oldBottom - oldTop))) {
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

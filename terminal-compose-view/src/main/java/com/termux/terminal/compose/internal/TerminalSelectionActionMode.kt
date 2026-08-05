package com.termux.terminal.compose.internal

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import com.termux.terminal.compose.TerminalCanvasConfig
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSelection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Bridges selection state to Android's native floating text action toolbar. */
internal class TerminalSelectionActionMode(
    private val hostView: View
) {
    private var actionMode: ActionMode? = null
    private var selection = TerminalSelection.EMPTY
    private var frame: TerminalFrame? = null
    private var selectedTextProvider: (() -> String)? = null
    private var metrics: TerminalMetrics? = null
    private var canvasPositionInWindow = Offset.Zero
    private var config = TerminalCanvasConfig()
    private var clearSelection: (() -> Unit)? = null
    private var disposed = false
    private var isHandleDragging = false

    private val handler = Handler(Looper.getMainLooper())
    private val showToolbar = Runnable {
        if (!isHandleDragging) actionMode?.hide(0)
    }
    private val keepToolbarHidden = Runnable { maintainToolbarHidden() }

    private val callback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (isHandleDragging) return false
            val showAsAction = MenuItem.SHOW_AS_ACTION_IF_ROOM or
                MenuItem.SHOW_AS_ACTION_WITH_TEXT
            menu.add(Menu.NONE, ACTION_COPY, Menu.NONE, android.R.string.copy)
                .setShowAsAction(showAsAction)
            menu.add(Menu.NONE, ACTION_PASTE, Menu.NONE, android.R.string.paste)
                .setShowAsAction(showAsAction)
            if (config.onMoreSelectionRequest != null) {
                menu.add(Menu.NONE, ACTION_MORE, Menu.NONE, "More")
            }
            updatePasteItem(menu)
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updatePasteItem(menu)
            return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (selection.isEmpty) return true

            val selectedText = selectedTextProvider?.invoke().orEmpty()
            when (item.itemId) {
                ACTION_COPY -> {
                    config.onCopyRequest(selectedText)
                    finishSelection()
                }

                ACTION_PASTE -> {
                    finishSelection()
                    config.onPasteRequest()
                }

                ACTION_MORE -> {
                    val moreAction = config.onMoreSelectionRequest ?: return true
                    finishSelection()
                    moreAction(selectedText)
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            val currentFrame = frame ?: return outRect.set(0, 0, 1, 1)
            val currentMetrics = metrics ?: return outRect.set(0, 0, 1, 1)
            if (selection.isEmpty) return outRect.set(0, 0, 1, 1)

            val firstRow = min(selection.startRow, selection.endRow)
            val lastRow = max(selection.startRow, selection.endRow)
            val firstColumn = min(selection.startCol, selection.endCol)
            val lastColumn = max(selection.startCol, selection.endCol)
            val viewLocation = IntArray(2)
            view.getLocationInWindow(viewLocation)
            val originX = canvasPositionInWindow.x.roundToInt() - viewLocation[0]
            val originY = canvasPositionInWindow.y.roundToInt() - viewLocation[1]
            val left = currentMetrics.columnToX(firstColumn).roundToInt() + originX
            val right = currentMetrics.columnToX(lastColumn + 1).roundToInt() + originX
            val top = currentMetrics.rowToY(firstRow, currentFrame.topRow).roundToInt() + originY
            val bottom = currentMetrics.rowToY(lastRow + 1, currentFrame.topRow).roundToInt() + originY
            setClampedRect(outRect, left, top, right, bottom, view.width, view.height)
        }
    }

    fun update(
        selection: TerminalSelection,
        frame: TerminalFrame?,
        selectedTextProvider: () -> String,
        metrics: TerminalMetrics,
        canvasPositionInWindow: Offset,
        config: TerminalCanvasConfig,
        clearSelection: () -> Unit
    ) {
        if (disposed) return
        this.selection = selection
        this.frame = frame
        this.selectedTextProvider = selectedTextProvider
        this.metrics = metrics
        this.canvasPositionInWindow = canvasPositionInWindow
        this.config = config
        this.clearSelection = clearSelection

        if (selection.isEmpty) {
            finishActionMode()
            return
        }
        if (isHandleDragging) {
            hideToolbarForHandleDrag()
            return
        }

        if (actionMode == null) {
            actionMode = try {
                hostView.startActionMode(callback, ActionMode.TYPE_FLOATING)
            } catch (_: IllegalStateException) {
                null
            }
        } else {
            actionMode?.invalidate()
        }
    }

    fun hideForHandleDrag() {
        isHandleDragging = true
        handler.removeCallbacks(showToolbar)
        hideToolbarForHandleDrag()
    }

    fun showAfterHandleDrag() {
        isHandleDragging = false
        handler.removeCallbacks(keepToolbarHidden)
        if (actionMode == null) return
        handler.removeCallbacks(showToolbar)
        handler.postDelayed(showToolbar, ViewConfiguration.getDoubleTapTimeout().toLong())
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        isHandleDragging = false
        handler.removeCallbacks(showToolbar)
        handler.removeCallbacks(keepToolbarHidden)
        finishActionMode()
        clearSelection = null
        frame = null
        selectedTextProvider = null
        metrics = null
    }

    private fun finishSelection() {
        finishActionMode()
        clearSelection?.invoke()
    }

    private fun hideToolbarForHandleDrag() {
        val mode = actionMode ?: return
        mode.hide(HANDLE_DRAG_HIDE_DURATION_MILLIS)
        handler.removeCallbacks(keepToolbarHidden)
        handler.postDelayed(keepToolbarHidden, HANDLE_DRAG_HIDE_REFRESH_MILLIS)
    }

    private fun maintainToolbarHidden() {
        if (!isHandleDragging) return
        hideToolbarForHandleDrag()
    }

    private fun finishActionMode() {
        handler.removeCallbacks(keepToolbarHidden)
        val mode = actionMode ?: return
        actionMode = null
        mode.finish()
    }

    private fun updatePasteItem(menu: Menu) {
        menu.findItem(ACTION_PASTE)?.isEnabled = hasPrimaryClip()
    }

    private fun hasPrimaryClip(): Boolean {
        val clipboard = hostView.context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? ClipboardManager
        return clipboard?.hasPrimaryClip() == true
    }

    private companion object {
        const val ACTION_COPY = 1
        const val ACTION_PASTE = 2
        const val ACTION_MORE = 3
        const val HANDLE_DRAG_HIDE_DURATION_MILLIS = 3_000L
        const val HANDLE_DRAG_HIDE_REFRESH_MILLIS = 250L
    }
}

private fun setClampedRect(
    outRect: Rect,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    viewWidth: Int,
    viewHeight: Int
) {
    val width = viewWidth.coerceAtLeast(1)
    val height = viewHeight.coerceAtLeast(1)
    val clampedLeft = left.coerceIn(0, width - 1)
    val clampedTop = top.coerceIn(0, height - 1)
    val clampedRight = right.coerceIn(clampedLeft + 1, width)
    val clampedBottom = bottom.coerceIn(clampedTop + 1, height)
    outRect.set(clampedLeft, clampedTop, clampedRight, clampedBottom)
}

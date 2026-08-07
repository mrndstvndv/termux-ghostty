package com.termux.terminal.compose.internal

import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalRow
import com.termux.terminal.compose.TerminalSelection

/**
 * Retained terminal renderer backed by Compose-managed RenderNodes. Each row
 * owns a display list and is re-recorded only when its content or row-local
 * overlays change.
 *
 * The renderer consumes only [TerminalFrame] and library-owned types; it never
 * calls back into a session or backend while drawing.
 */
internal class TerminalRenderNodeRenderer(
    private val graphicsContext: GraphicsContext,
    private val rowRenderer: TerminalRowRenderer,
    private val shaders: List<CompiledShader>
) {
    private val hasAnimatedShader = shaders.any { it.definition.usesTimeUniform }
    private val animatedBitmapRenderer = AnimatedTerminalBitmapRenderer(shaders, rowRenderer)

    private var parentLayer = graphicsContext.createGraphicsLayer()
    private var rows: Array<TerminalRowState> = emptyArray()
    private var visibleRowCount = 0
    private var width = -1
    private var height = -1
    private var lineHeight = -1
    private var backgroundColor = Color.Unspecified
    private var parentDisplayListDirty = true
    private var lastProcessedContentVersion = Int.MIN_VALUE
    private var lastSelection = TerminalSelection.EMPTY
    private var lastCursor = TerminalCursor(Int.MIN_VALUE, Int.MIN_VALUE, false, Int.MIN_VALUE)
    private var lastReverseVideo = false
    private var lastFrameSequence = Long.MIN_VALUE
    private var lastLinkFrameSequence = Long.MIN_VALUE
    private var lastPaletteVersion = Int.MIN_VALUE
    private var paletteVersion = 0
    private var boundShaders: List<CompiledShader> = emptyList()
    private var shaderResolutionWidth = Float.NaN
    private var shaderResolutionHeight = Float.NaN

    fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        contentVersion: Int,
        selection: TerminalSelection,
        timeSeconds: Float
    ) {
        if (hasAnimatedShader) {
            animatedBitmapRenderer.draw(
                drawScope = drawScope,
                frame = frame,
                contentVersion = contentVersion,
                selection = selection,
                timeSeconds = timeSeconds
            )
            return
        }

        val targetWidth = drawScope.size.width.toInt().coerceAtLeast(1)
        val targetHeight = drawScope.size.height.toInt().coerceAtLeast(1)
        ensureLayout(frame, targetWidth, targetHeight)
        updateRowsIfNeeded(drawScope, frame, contentVersion, selection)
        updateBackground(frame)

        if (shaders.isEmpty()) {
            drawScope.drawRect(backgroundColor)
            for (rowIndex in 0 until visibleRowCount) {
                drawScope.drawLayer(rows[rowIndex].layer)
            }
            return
        }

        updateParentDisplayList(drawScope)
        updateShaders(timeSeconds)
        drawScope.drawLayer(parentLayer)
    }

    fun release() {
        animatedBitmapRenderer.release()
        parentLayer.renderEffect = null
        releaseRows()
        rows = emptyArray()
        visibleRowCount = 0
        graphicsContext.releaseGraphicsLayer(parentLayer)
    }

    private fun ensureLayout(frame: TerminalFrame, targetWidth: Int, targetHeight: Int) {
        val nextLineHeight = rowRenderer.lineSpacingPx
        val visibleRowsChanged = visibleRowCount != frame.rowsVisible
        val parentSizeChanged = height != targetHeight
        val geometryChanged = nextLineHeight != lineHeight || width != targetWidth
        if (!geometryChanged && !visibleRowsChanged && !parentSizeChanged) {
            return
        }

        // Row layers' vertical offsets depend on the line height, so a font
        // metric change still rebuilds every layer. Any other size change keeps
        // and reuses the retained layers: reflowed content changes the row
        // data, and updateRowsIfNeeded re-records only the layers whose content
        // or overlays really changed. This avoids the release/recreate churn
        // (and the resulting one-frame layer blanking) on every intermediate
        // width during a drag-resize.
        if (nextLineHeight != lineHeight) {
            parentLayer.renderEffect = null
            releaseRows()
            graphicsContext.releaseGraphicsLayer(parentLayer)
            width = targetWidth
            height = targetHeight
            lineHeight = nextLineHeight
            parentLayer = graphicsContext.createGraphicsLayer()
            rows = Array(frame.rowsVisible) { rowIndex -> createRowLayer(rowIndex) }
            visibleRowCount = frame.rowsVisible
            boundShaders = emptyList()
            shaderResolutionWidth = Float.NaN
            shaderResolutionHeight = Float.NaN
            parentDisplayListDirty = true
            invalidateProcessedFrame()
            return
        }

        if (width != targetWidth) {
            width = targetWidth
            parentDisplayListDirty = true
        }
        if (visibleRowsChanged) {
            ensureRowCapacity(frame.rowsVisible)
            visibleRowCount = frame.rowsVisible
            parentDisplayListDirty = true
            invalidateProcessedFrame()
        }
        if (parentSizeChanged) {
            height = targetHeight
            parentDisplayListDirty = true
        }
    }

    private fun ensureRowCapacity(requiredRows: Int) {
        if (requiredRows <= rows.size) return

        val existingRows = rows
        rows = Array(requiredRows) { rowIndex ->
            if (rowIndex < existingRows.size) existingRows[rowIndex]
            else createRowLayer(rowIndex)
        }
    }

    private fun createRowLayer(rowIndex: Int): TerminalRowState =
        TerminalRowState(
            graphicsContext.createGraphicsLayer().apply {
                topLeft = IntOffset(0, rowIndex * lineHeight)
            }
        )

    private fun releaseRows() {
        rows.forEach { row -> graphicsContext.releaseGraphicsLayer(row.layer) }
    }

    private fun updateRowsIfNeeded(
        drawScope: DrawScope,
        frame: TerminalFrame,
        contentVersion: Int,
        selection: TerminalSelection
    ) {
        val cursor = frame.cursor
        val reverseVideo = frame.reverseVideo
        val frameChanged = frame.sequence != lastFrameSequence
        val linkFrameSequence = frame.linkLayout?.frameSequence ?: Long.MIN_VALUE
        val linkLayoutChanged = linkFrameSequence != lastLinkFrameSequence
        if (frameChanged && frame.palette.version != lastPaletteVersion) paletteVersion++

        val overlaysUnchanged = selection == lastSelection &&
            cursor == lastCursor &&
            reverseVideo == lastReverseVideo
        val frameAndLinksUnchanged = !frameChanged && !linkLayoutChanged
        val contentUnchanged = contentVersion == lastProcessedContentVersion
        val unchanged = contentUnchanged && overlaysUnchanged
        if (unchanged && frameAndLinksUnchanged) return

        for (rowIndex in 0 until visibleRowCount) {
            val row = frame.row(rowIndex)
            if (row != null) {
                val absoluteRow = frame.topRow + rowIndex
                val selectionStart = if (absoluteRow == selection.startRow) selection.startCol else -1
                val selectionEnd = rowSelectionEnd(absoluteRow, selection, frame.columns)
                val rowCursorX = if (cursor.visible && absoluteRow == cursor.row) cursor.column else -1
                val hints = RowRenderHints(selectionStart, selectionEnd, rowCursorX, cursor.style, reverseVideo)
                val rowState = rows[rowIndex]
                val contentChanged = rowContentOutdated(rowState, row, linkFrameSequence)
                val overlayChanged = rowSelectionOutdated(rowState, hints) ||
                    rowCursorOutdated(rowState, hints) ||
                    rowStyleOutdated(rowState, hints, paletteVersion)
                if (contentChanged || overlayChanged) {
                    recordRowLayer(drawScope, frame, rowIndex, row, hints)
                }
            }
        }

        lastProcessedContentVersion = contentVersion
        lastSelection = selection
        lastCursor = cursor
        lastReverseVideo = reverseVideo
        lastFrameSequence = frame.sequence
        lastLinkFrameSequence = linkFrameSequence
        lastPaletteVersion = frame.palette.version
    }

    private fun rowSelectionEnd(absoluteRow: Int, selection: TerminalSelection, columns: Int): Int = when {
        absoluteRow < selection.startRow || absoluteRow > selection.endRow -> -1
        absoluteRow == selection.endRow -> selection.endCol
        else -> columns
    }

    private fun recordRowLayer(
        drawScope: DrawScope,
        frame: TerminalFrame,
        rowIndex: Int,
        row: TerminalRow,
        hints: RowRenderHints
    ) {
        val rowState = rows[rowIndex]
        rowState.layer.record(
            density = drawScope,
            layoutDirection = drawScope.layoutDirection,
            size = IntSize(width, lineHeight)
        ) {
            drawIntoCanvas { canvas ->
                rowRenderer.renderRow(
                    canvas = canvas.nativeCanvas,
                    frame = frame,
                    rowIndex = rowIndex,
                    hints = hints
                )
            }
        }
        rowState.applyFrame(
            row,
            hints,
            paletteVersion,
            frame.linkLayout?.frameSequence ?: Long.MIN_VALUE
        )
        parentDisplayListDirty = true
    }

    private fun updateBackground(frame: TerminalFrame) {
        val colorIndex = if (frame.reverseVideo) {
            TerminalPalette.COLOR_INDEX_FOREGROUND
        } else {
            TerminalPalette.COLOR_INDEX_BACKGROUND
        }
        val nextColor = frame.palette.color(colorIndex)
        if (backgroundColor.toArgb() == nextColor) return

        backgroundColor = Color(nextColor)
        parentDisplayListDirty = true
    }

    private fun updateParentDisplayList(drawScope: DrawScope) {
        if (!parentDisplayListDirty) return

        parentLayer.record(
            density = drawScope,
            layoutDirection = drawScope.layoutDirection,
            size = IntSize(width, height)
        ) {
            drawRect(backgroundColor)
            for (rowIndex in 0 until visibleRowCount) {
                drawLayer(rows[rowIndex].layer)
            }
        }
        parentDisplayListDirty = false
    }

    private fun updateShaders(timeSeconds: Float) {
        val shadersChanged = shaders.size != boundShaders.size ||
            shaders.indices.any { index -> shaders[index].shader !== boundShaders[index].shader }
        if (shadersChanged) {
            shaderResolutionWidth = Float.NaN
            shaderResolutionHeight = Float.NaN
        }

        val resolutionChanged = shaders.any { it.definition.usesResolutionUniform } &&
            (shaderResolutionWidth != width.toFloat() || shaderResolutionHeight != height.toFloat())
        shaders.forEach { compiledShader ->
            compiledShader.updateUniforms(
                timeSeconds = timeSeconds,
                width = width.toFloat(),
                height = height.toFloat(),
                updateResolution = resolutionChanged
            )
        }
        if (resolutionChanged) {
            shaderResolutionWidth = width.toFloat()
            shaderResolutionHeight = height.toFloat()
        }
        if (!shadersChanged && !resolutionChanged) return

        var renderEffect = AndroidRenderEffect.createRuntimeShaderEffect(shaders.first().shader, "content")
        shaders.drop(1).forEach { compiledShader ->
            val outerEffect = AndroidRenderEffect.createRuntimeShaderEffect(compiledShader.shader, "content")
            renderEffect = AndroidRenderEffect.createChainEffect(outerEffect, renderEffect)
        }
        parentLayer.renderEffect = renderEffect.asComposeRenderEffect()
        boundShaders = shaders
    }

    private fun invalidateProcessedFrame() {
        lastProcessedContentVersion = Int.MIN_VALUE
        lastSelection = TerminalSelection.EMPTY
        lastCursor = TerminalCursor(Int.MIN_VALUE, Int.MIN_VALUE, false, Int.MIN_VALUE)
        lastReverseVideo = false
        lastFrameSequence = Long.MIN_VALUE
        lastLinkFrameSequence = Long.MIN_VALUE
        lastPaletteVersion = Int.MIN_VALUE
    }
}

private fun Color.toArgb(): Int {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

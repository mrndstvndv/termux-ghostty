package com.mrndtvndv.term.ui.workspace

import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer

/**
 * Retained terminal renderer backed by Compose-managed RenderNodes. Each row owns a display list
 * and is re-recorded only when its content or row-local overlays change.
 */
internal class TerminalRenderNodeRenderer(
    private val graphicsContext: GraphicsContext,
    private val shaders: List<CompiledShader>
) {
    private val hasAnimatedShader = shaders.any { it.definition.usesTimeUniform }
    private val animatedBitmapRenderer = AnimatedTerminalBitmapRenderer(shaders)
    private class RowState(
        val layer: GraphicsLayer
    ) {
        var contentHash = Long.MIN_VALUE
        var selectionStart = Int.MIN_VALUE
        var selectionEnd = Int.MIN_VALUE
        var cursorX = Int.MIN_VALUE
        var cursorStyle = Int.MIN_VALUE
        var reverseVideo = false
        var paletteVersion = Int.MIN_VALUE
    }

    private var parentLayer = graphicsContext.createGraphicsLayer()
    private var rows: Array<RowState> = emptyArray()
    private var visibleRowCount = 0
    private var renderer: TerminalRenderer? = null
    private var width = -1
    private var height = -1
    private var lineHeight = -1
    private var backgroundColor = Color.Unspecified
    private var parentDisplayListDirty = true
    private var lastProcessedContentVersion = Int.MIN_VALUE
    private var lastSelectionY1 = Int.MIN_VALUE
    private var lastSelectionY2 = Int.MIN_VALUE
    private var lastSelectionX1 = Int.MIN_VALUE
    private var lastSelectionX2 = Int.MIN_VALUE
    private var lastCursorCol = Int.MIN_VALUE
    private var lastCursorRow = Int.MIN_VALUE
    private var lastCursorVisible = false
    private var lastCursorStyle = Int.MIN_VALUE
    private var lastReverseVideo = false
    private var lastFrameSequence = Long.MIN_VALUE
    private var paletteVersion = 0
    private var boundShaders: List<CompiledShader> = emptyList()
    private var shaderResolutionWidth = Float.NaN
    private var shaderResolutionHeight = Float.NaN

    fun draw(
        drawScope: DrawScope,
        snapshot: ScreenSnapshot,
        renderer: TerminalRenderer,
        contentVersion: Int,
        selection: RenderSelection,
        timeSeconds: Float
    ) {
        if (hasAnimatedShader) {
            animatedBitmapRenderer.draw(
                drawScope = drawScope,
                snapshot = snapshot,
                renderer = renderer,
                contentVersion = contentVersion,
                selection = selection,
                timeSeconds = timeSeconds
            )
            return
        }

        val targetWidth = drawScope.size.width.toInt().coerceAtLeast(1)
        val targetHeight = drawScope.size.height.toInt().coerceAtLeast(1)
        ensureLayout(snapshot, renderer, targetWidth, targetHeight)
        updateRowsIfNeeded(drawScope, snapshot, renderer, contentVersion, selection)
        updateBackground(snapshot)

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

    private fun ensureLayout(
        snapshot: ScreenSnapshot,
        nextRenderer: TerminalRenderer,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val nextLineHeight = nextRenderer.fontLineSpacing
        val rowGeometryChanged =
            renderer !== nextRenderer ||
                width != targetWidth ||
                lineHeight != nextLineHeight
        val visibleRowsChanged = visibleRowCount != snapshot.rows
        val parentSizeChanged = height != targetHeight
        if (!rowGeometryChanged && !visibleRowsChanged && !parentSizeChanged) return

        if (rowGeometryChanged) {
            parentLayer.renderEffect = null
            releaseRows()
            graphicsContext.releaseGraphicsLayer(parentLayer)
            renderer = nextRenderer
            width = targetWidth
            height = targetHeight
            lineHeight = nextLineHeight
            parentLayer = graphicsContext.createGraphicsLayer()
            rows = Array(snapshot.rows) { rowIndex -> createRowLayer(rowIndex) }
            visibleRowCount = snapshot.rows
            boundShaders = emptyList()
            shaderResolutionWidth = Float.NaN
            shaderResolutionHeight = Float.NaN
            parentDisplayListDirty = true
            invalidateProcessedFrame()
            return
        }

        if (visibleRowsChanged) {
            ensureRowCapacity(snapshot.rows)
            visibleRowCount = snapshot.rows
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

    private fun createRowLayer(rowIndex: Int): RowState =
        RowState(
            graphicsContext.createGraphicsLayer().apply {
                topLeft = IntOffset(0, rowIndex * lineHeight)
            }
        )

    private fun releaseRows() {
        rows.forEach { row -> graphicsContext.releaseGraphicsLayer(row.layer) }
    }

    // This is one allocation-free pass over retained rows; splitting its state comparison into
    // temporary key objects would add garbage to the terminal frame hot path.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "ComplexCondition")
    private fun updateRowsIfNeeded(
        drawScope: DrawScope,
        snapshot: ScreenSnapshot,
        terminalRenderer: TerminalRenderer,
        contentVersion: Int,
        selection: RenderSelection
    ) {
        val cursorCol = snapshot.cursorCol
        val cursorRow = snapshot.cursorRow
        val cursorVisible = snapshot.isCursorVisible
        val cursorStyle = snapshot.cursorStyle
        val reverseVideo = snapshot.isReverseVideo
        val frameChanged = snapshot.frameSequence != lastFrameSequence
        if (frameChanged && snapshot.hasPaletteUpdate()) paletteVersion++

        val overlaysUnchanged =
            selection.y1 == lastSelectionY1 &&
                selection.y2 == lastSelectionY2 &&
                selection.x1 == lastSelectionX1 &&
                selection.x2 == lastSelectionX2 &&
                cursorCol == lastCursorCol &&
                cursorRow == lastCursorRow &&
                cursorVisible == lastCursorVisible &&
                cursorStyle == lastCursorStyle &&
                reverseVideo == lastReverseVideo
        if (contentVersion == lastProcessedContentVersion && overlaysUnchanged && !frameChanged) return

        for (rowIndex in 0 until visibleRowCount) {
            val rowState = rows[rowIndex]
            val absoluteRow = snapshot.topRow + rowIndex
            val selectionStart = if (absoluteRow == selection.y1) selection.x1 else -1
            val selectionEnd = when {
                absoluteRow < selection.y1 || absoluteRow > selection.y2 -> -1
                absoluteRow == selection.y2 -> selection.x2
                else -> snapshot.columns
            }
            val rowCursorX = if (cursorVisible && absoluteRow == cursorRow) cursorCol else -1
            val contentHash = snapshot.getRow(rowIndex).contentHash
            if (
                rowState.contentHash == contentHash &&
                rowState.selectionStart == selectionStart &&
                rowState.selectionEnd == selectionEnd &&
                rowState.cursorX == rowCursorX &&
                rowState.cursorStyle == cursorStyle &&
                rowState.reverseVideo == reverseVideo &&
                rowState.paletteVersion == paletteVersion
            ) {
                continue
            }

            rowState.layer.record(
                density = drawScope,
                layoutDirection = drawScope.layoutDirection,
                size = IntSize(width, lineHeight)
            ) {
                drawIntoCanvas { canvas ->
                    terminalRenderer.renderRow(
                        snapshot,
                        canvas.nativeCanvas,
                        rowIndex,
                        selectionStart,
                        selectionEnd,
                        rowCursorX,
                        cursorStyle,
                        reverseVideo
                    )
                }
            }
            rowState.contentHash = contentHash
            rowState.selectionStart = selectionStart
            rowState.selectionEnd = selectionEnd
            rowState.cursorX = rowCursorX
            rowState.cursorStyle = cursorStyle
            rowState.reverseVideo = reverseVideo
            rowState.paletteVersion = paletteVersion
            parentDisplayListDirty = true
        }

        lastProcessedContentVersion = contentVersion
        lastSelectionY1 = selection.y1
        lastSelectionY2 = selection.y2
        lastSelectionX1 = selection.x1
        lastSelectionX2 = selection.x2
        lastCursorCol = cursorCol
        lastCursorRow = cursorRow
        lastCursorVisible = cursorVisible
        lastCursorStyle = cursorStyle
        lastReverseVideo = reverseVideo
        lastFrameSequence = snapshot.frameSequence
    }

    private fun updateBackground(snapshot: ScreenSnapshot) {
        val color = if (snapshot.isReverseVideo) {
            snapshot.getPaletteColor(TextStyle.COLOR_INDEX_FOREGROUND)
        } else {
            snapshot.getPaletteColor(TextStyle.COLOR_INDEX_BACKGROUND)
        }
        val nextBackgroundColor = Color(color)
        if (backgroundColor == nextBackgroundColor) return

        backgroundColor = nextBackgroundColor
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

        val updateResolution = shaders.any { it.definition.usesResolutionUniform } &&
            (shaderResolutionWidth != width.toFloat() || shaderResolutionHeight != height.toFloat())
        shaders.forEach { compiledShader ->
            compiledShader.shader.updateUniforms(
                timeSeconds = timeSeconds,
                width = width.toFloat(),
                height = height.toFloat(),
                updateTimeUniform = compiledShader.definition.usesTimeUniform,
                updateResolutionUniform = updateResolution &&
                    compiledShader.definition.usesResolutionUniform
            )
        }
        if (updateResolution) {
            shaderResolutionWidth = width.toFloat()
            shaderResolutionHeight = height.toFloat()
        }
        if (!shadersChanged && !updateResolution) return

        var renderEffect = AndroidRenderEffect
            .createRuntimeShaderEffect(shaders.first().shader, "content")
        shaders.drop(1).forEach { compiledShader ->
            val outerEffect = AndroidRenderEffect
                .createRuntimeShaderEffect(compiledShader.shader, "content")
            renderEffect = AndroidRenderEffect.createChainEffect(outerEffect, renderEffect)
        }
        parentLayer.renderEffect = renderEffect.asComposeRenderEffect()
        boundShaders = shaders
    }

    private fun invalidateProcessedFrame() {
        lastProcessedContentVersion = Int.MIN_VALUE
        lastSelectionY1 = Int.MIN_VALUE
        lastSelectionY2 = Int.MIN_VALUE
        lastSelectionX1 = Int.MIN_VALUE
        lastSelectionX2 = Int.MIN_VALUE
        lastCursorCol = Int.MIN_VALUE
        lastCursorRow = Int.MIN_VALUE
        lastCursorVisible = false
        lastCursorStyle = Int.MIN_VALUE
        lastReverseVideo = false
        lastFrameSequence = Long.MIN_VALUE
    }
}

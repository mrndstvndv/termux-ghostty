package com.termux.terminal.compose.internal

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
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalLinkLayout
import com.termux.terminal.compose.TerminalPalette
import com.termux.terminal.compose.TerminalSelection

/**
 * Retained terminal renderer backed by Compose-managed RenderNodes. One layer
 * per visible row preserves rendered glyphs while scrolling; a stable parent
 * display list presents those rows through a single draw operation.
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
    private var rowLayers: Array<TerminalRowLayerState> = emptyArray()
    private var dirtyRows = BooleanArray(0)
    private var visibleRowCount = 0
    // Reusable per-row overlay scratch, filled during the dirty scan and
    // consumed while recording. Avoids allocating RowRenderHints per row (and
    // twice per dirty row) on every changed frame.
    private var hintSelectionStarts = IntArray(0)
    private var hintSelectionEnds = IntArray(0)
    private var hintCursorXs = IntArray(0)
    private var hintLinkContentHashes = LongArray(0)
    private val rowHintsScratch = RowRenderHints(-1, -1, -1, TerminalCursor.STYLE_BLOCK, false)
    // Reusable sink for the scroll layer rotation.
    private var scrollScratch: Array<TerminalRowLayerState> = emptyArray()
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
    private var lastLinkLayout: TerminalLinkLayout? = null
    private var lastPaletteVersion = Int.MIN_VALUE
    private var paletteVersion = 0
    private var boundShaders: List<CompiledShader> = emptyList()
    private var shaderResolutionWidth = Float.NaN
    private var shaderResolutionHeight = Float.NaN
    private var forceFullTileRecord = true

    private var lastTopRow = Int.MIN_VALUE
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
        updateParentDisplayList(drawScope)
        if (shaders.isNotEmpty()) updateShaders(timeSeconds)
        drawScope.drawLayer(parentLayer)
    }

    fun release() {
        animatedBitmapRenderer.release()
        parentLayer.renderEffect = null
        releaseRowLayers()
        rowLayers = emptyArray()
        dirtyRows = BooleanArray(0)
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

        // Row offsets depend on line height, so a font metric change rebuilds
        // every render resource. Pixel-only size changes retain row layers;
        // backend grid coalescing decides when reflow actually changes rows.
        if (nextLineHeight != lineHeight) {
            parentLayer.renderEffect = null
            releaseRowLayers()
            graphicsContext.releaseGraphicsLayer(parentLayer)
            width = targetWidth
            height = targetHeight
            lineHeight = nextLineHeight
            parentLayer = graphicsContext.createGraphicsLayer()
            rowLayers = Array(frame.rowsVisible) { createRowLayer() }
            dirtyRows = BooleanArray(frame.rowsVisible)
            visibleRowCount = frame.rowsVisible
            ensureHintScratch(frame.rowsVisible)
            boundShaders = emptyList()
            shaderResolutionWidth = Float.NaN
            shaderResolutionHeight = Float.NaN
            parentDisplayListDirty = true
            forceFullTileRecord = true
            invalidateProcessedFrame()
            return
        }

        if (width != targetWidth) {
            width = targetWidth
            parentDisplayListDirty = true
        }
        if (visibleRowsChanged) {
            ensureCapacity(frame.rowsVisible)
            visibleRowCount = frame.rowsVisible
            parentDisplayListDirty = true
            forceFullTileRecord = true
            invalidateProcessedFrame()
        }
        if (parentSizeChanged) {
            height = targetHeight
            parentDisplayListDirty = true
        }
    }

    private fun ensureCapacity(requiredRows: Int) {
        if (requiredRows <= rowLayers.size) return
        val existingLayers = rowLayers
        rowLayers = Array(requiredRows) { rowIndex ->
            if (rowIndex < existingLayers.size) existingLayers[rowIndex] else createRowLayer()
        }
        dirtyRows = BooleanArray(requiredRows)
        ensureHintScratch(requiredRows)
    }

    private fun ensureHintScratch(rows: Int) {
        if (rows <= hintSelectionStarts.size) return
        hintSelectionStarts = IntArray(rows)
        hintSelectionEnds = IntArray(rows)
        hintCursorXs = IntArray(rows)
        hintLinkContentHashes = LongArray(rows)
    }

    private fun createRowLayer(): TerminalRowLayerState =
        TerminalRowLayerState(graphicsContext.createGraphicsLayer())

    private fun releaseRowLayers() {
        rowLayers.forEach { row -> graphicsContext.releaseGraphicsLayer(row.layer) }
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
        val linkLayoutChanged = frame.linkLayout !== lastLinkLayout
        if (frameChanged && frame.palette.version != lastPaletteVersion) paletteVersion++

        val overlaysUnchanged = selection == lastSelection &&
            cursor == lastCursor &&
            reverseVideo == lastReverseVideo
        val frameAndLinksUnchanged = !frameChanged && !linkLayoutChanged
        val contentUnchanged = contentVersion == lastProcessedContentVersion
        val unchanged = contentUnchanged && overlaysUnchanged
        if (!forceFullTileRecord && unchanged && frameAndLinksUnchanged) return

        repositionRowsForScroll(frame)
        markDirtyRows(frame, selection)
        recordDirtyRows(drawScope, frame)

        lastProcessedContentVersion = contentVersion
        lastSelection = selection
        lastCursor = cursor
        lastReverseVideo = reverseVideo
        lastFrameSequence = frame.sequence
        lastLinkLayout = frame.linkLayout
        lastPaletteVersion = frame.palette.version
        lastTopRow = frame.topRow
        forceFullTileRecord = false
    }

    private fun markDirtyRows(frame: TerminalFrame, selection: TerminalSelection) {
        dirtyRows.fill(false, 0, visibleRowCount)
        val topRow = frame.topRow
        val columns = frame.columns
        val cursor = frame.cursor
        val reverseVideo = frame.reverseVideo
        val selectionStartRow = selection.startRow
        val selectionEndRow = selection.endRow
        val linkLayout = frame.linkLayout
        for (rowIndex in 0 until visibleRowCount) {
            val row = frame.row(rowIndex)
            val rowLayer = rowLayers[rowIndex]
            if (row == null) {
                if (rowLayer.state.contentHash != Long.MIN_VALUE) {
                    dirtyRows[rowIndex] = true
                }
                continue
            }
            val absoluteRow = topRow + rowIndex
            val selectionStart = if (absoluteRow == selectionStartRow) selection.startCol else -1
            val selectionEnd = when {
                absoluteRow < selectionStartRow || absoluteRow > selectionEndRow -> -1
                absoluteRow == selectionEndRow -> selection.endCol
                else -> columns
            }
            val cursorX = if (cursor.visible && absoluteRow == cursor.row) cursor.column else -1
            val linkContentHash = linkLayout?.rowContentHash(rowIndex) ?: 0L
            hintSelectionStarts[rowIndex] = selectionStart
            hintSelectionEnds[rowIndex] = selectionEnd
            hintCursorXs[rowIndex] = cursorX
            hintLinkContentHashes[rowIndex] = linkContentHash
            val contentChanged = rowContentOutdated(rowLayer.state, row, linkContentHash)
            val overlayChanged = rowSelectionOutdated(rowLayer.state, selectionStart, selectionEnd) ||
                rowCursorOutdated(rowLayer.state, cursorX, cursor.style) ||
                rowStyleOutdated(rowLayer.state, reverseVideo, paletteVersion)
            dirtyRows[rowIndex] = forceFullTileRecord || contentChanged || overlayChanged
        }
    }

    private fun recordDirtyRows(
        drawScope: DrawScope,
        frame: TerminalFrame
    ) {
        var recordedRow = false
        for (rowIndex in 0 until visibleRowCount) {
            if (dirtyRows[rowIndex]) {
                recordRowLayer(drawScope, frame, rowIndex)
                recordedRow = true
            }
        }
        if (recordedRow) parentDisplayListDirty = true
    }

    private fun repositionRowsForScroll(frame: TerminalFrame) {
        if (lastTopRow == Int.MIN_VALUE) return
        val delta = frame.topRow - lastTopRow
        if (!canReuseRowsForScroll(frame, delta)) return

        // Circular rotation of the layer pool without allocating a new array:
        // viewport row i takes the old layer for row (i + delta) modulo count.
        val count = visibleRowCount
        val distance = kotlin.math.abs(delta)
        if (scrollScratch.size < distance) {
            scrollScratch = java.util.Arrays.copyOf(scrollScratch, distance)
        }
        if (delta > 0) {
            System.arraycopy(rowLayers, 0, scrollScratch, 0, distance)
            System.arraycopy(rowLayers, distance, rowLayers, 0, count - distance)
            System.arraycopy(scrollScratch, 0, rowLayers, count - distance, distance)
            for (rowIndex in (count - distance) until count) {
                rowLayers[rowIndex].state.clear()
            }
        } else {
            System.arraycopy(rowLayers, count - distance, scrollScratch, 0, distance)
            System.arraycopy(rowLayers, 0, rowLayers, distance, count - distance)
            System.arraycopy(scrollScratch, 0, rowLayers, 0, distance)
            for (rowIndex in 0 until distance) {
                rowLayers[rowIndex].state.clear()
            }
        }
        for (rowIndex in 0 until count) {
            rowLayers[rowIndex].layer.topLeft = IntOffset(0, rowIndex * lineHeight)
        }
        parentDisplayListDirty = true
    }

    private fun canReuseRowsForScroll(frame: TerminalFrame, delta: Int): Boolean {
        if (delta == 0 || kotlin.math.abs(delta) >= visibleRowCount) return false
        val firstSharedRow = maxOf(0, -delta)
        val lastSharedRowExclusive = minOf(visibleRowCount, visibleRowCount - delta)
        return (firstSharedRow until lastSharedRowExclusive).all { rowIndex ->
            frame.row(rowIndex) === rowLayers[rowIndex + delta].state.row
        }
    }

    private fun recordRowLayer(
        drawScope: DrawScope,
        frame: TerminalFrame,
        rowIndex: Int
    ) {
        val rowLayer = rowLayers[rowIndex]
        rowLayer.layer.topLeft = IntOffset(0, rowIndex * lineHeight)
        val row = frame.row(rowIndex)
        rowHintsScratch.selectionStart = hintSelectionStarts[rowIndex]
        rowHintsScratch.selectionEnd = hintSelectionEnds[rowIndex]
        rowHintsScratch.cursorX = hintCursorXs[rowIndex]
        rowHintsScratch.cursorStyle = frame.cursor.style
        rowHintsScratch.reverseVideo = frame.reverseVideo
        // GraphicsLayer may replay this callback after its display list is discarded during
        // backgrounding. Capture a row-local copy instead of the shared scan scratch.
        val recordedHints = rowHintsScratch.snapshotForRetainedLayer()
        val linkContentHash = hintLinkContentHashes[rowIndex]
        rowLayer.layer.record(
            density = drawScope,
            layoutDirection = drawScope.layoutDirection,
            size = IntSize(width, lineHeight)
        ) {
            drawIntoCanvas { canvas ->
                if (row != null) {
                    rowRenderer.renderRow(
                        canvas = canvas.nativeCanvas,
                        frame = frame,
                        rowIndex = rowIndex,
                        hints = recordedHints,
                        baselineY = lineHeight.toFloat()
                    )
                }
            }
        }
        if (row == null) {
            rowLayer.state.clear()
        } else {
            rowLayer.state.applyFrame(row, recordedHints, paletteVersion, linkContentHash)
        }
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
                drawLayer(rowLayers[rowIndex].layer)
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
        lastLinkLayout = null
        lastPaletteVersion = Int.MIN_VALUE
        lastTopRow = Int.MIN_VALUE
    }
}

private class TerminalRowLayerState(
    val layer: GraphicsLayer,
    val state: TerminalRowState = TerminalRowState()
)

private fun Color.toArgb(): Int {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

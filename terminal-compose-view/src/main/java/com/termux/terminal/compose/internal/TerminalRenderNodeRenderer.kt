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
 * Retained terminal renderer backed by Compose-managed RenderNodes. Four-row
 * tiles bound sparse redraw work while reducing layer fan-out, and one stable
 * parent display list presents all tiles through a single draw operation.
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
    private val tilePlanner = TerminalRenderTilePlanner()

    private var parentLayer = graphicsContext.createGraphicsLayer()
    private var rowStates: Array<TerminalRowState> = emptyArray()
    private var tiles: Array<TerminalTileState> = emptyArray()
    private var dirtyRows = BooleanArray(0)
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
    private var lastLinkLayout: TerminalLinkLayout? = null
    private var lastPaletteVersion = Int.MIN_VALUE
    private var paletteVersion = 0
    private var boundShaders: List<CompiledShader> = emptyList()
    private var shaderResolutionWidth = Float.NaN
    private var shaderResolutionHeight = Float.NaN
    private var forceFullTileRecord = true

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
        releaseTiles()
        rowStates = emptyArray()
        tiles = emptyArray()
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

        // Tile offsets depend on line height, so a font metric change rebuilds
        // every render resource. Pixel-only size changes retain the tiles;
        // backend grid coalescing decides when reflow actually changes rows.
        if (nextLineHeight != lineHeight) {
            parentLayer.renderEffect = null
            releaseTiles()
            graphicsContext.releaseGraphicsLayer(parentLayer)
            width = targetWidth
            height = targetHeight
            lineHeight = nextLineHeight
            parentLayer = graphicsContext.createGraphicsLayer()
            rowStates = Array(frame.rowsVisible) { TerminalRowState() }
            tiles = Array(tilePlanner.tileCount(frame.rowsVisible), ::createTileLayer)
            dirtyRows = BooleanArray(frame.rowsVisible)
            visibleRowCount = frame.rowsVisible
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
        if (requiredRows > rowStates.size) {
            val existingRows = rowStates
            rowStates = Array(requiredRows) { rowIndex ->
                if (rowIndex < existingRows.size) existingRows[rowIndex] else TerminalRowState()
            }
            dirtyRows = BooleanArray(requiredRows)
        }
        val requiredTiles = tilePlanner.tileCount(requiredRows)
        if (requiredTiles > tiles.size) {
            val existingTiles = tiles
            tiles = Array(requiredTiles) { tileIndex ->
                if (tileIndex < existingTiles.size) existingTiles[tileIndex] else createTileLayer(tileIndex)
            }
        }
    }

    private fun createTileLayer(tileIndex: Int): TerminalTileState =
        TerminalTileState(
            graphicsContext.createGraphicsLayer().apply {
                topLeft = IntOffset(0, tilePlanner.firstRow(tileIndex) * lineHeight)
            }
        )

    private fun releaseTiles() {
        tiles.forEach { tile -> graphicsContext.releaseGraphicsLayer(tile.layer) }
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

        dirtyRows.fill(false, 0, visibleRowCount)
        for (rowIndex in 0 until visibleRowCount) {
            val row = frame.row(rowIndex)
            if (row != null) {
                val hints = rowRenderHints(frame, selection, rowIndex)
                val rowState = rowStates[rowIndex]
                val linkContentHash = frame.linkLayout?.rowContentHash(rowIndex) ?: 0L
                val contentChanged = rowContentOutdated(rowState, row, linkContentHash)
                val overlayChanged = rowSelectionOutdated(rowState, hints) ||
                    rowCursorOutdated(rowState, hints) ||
                    rowStyleOutdated(rowState, hints, paletteVersion)
                dirtyRows[rowIndex] = forceFullTileRecord || contentChanged || overlayChanged
            } else if (rowStates[rowIndex].contentHash != Long.MIN_VALUE) {
                dirtyRows[rowIndex] = true
            }
        }
        val work = tilePlanner.plan(visibleRowCount, dirtyRows)
        repeat(work.recordedLayerCount) { workIndex ->
            recordTileLayer(drawScope, frame, selection, work, workIndex)
        }

        lastProcessedContentVersion = contentVersion
        lastSelection = selection
        lastCursor = cursor
        lastReverseVideo = reverseVideo
        lastFrameSequence = frame.sequence
        lastLinkLayout = frame.linkLayout
        lastPaletteVersion = frame.palette.version
        forceFullTileRecord = false
    }

    private fun rowSelectionEnd(absoluteRow: Int, selection: TerminalSelection, columns: Int): Int = when {
        absoluteRow < selection.startRow || absoluteRow > selection.endRow -> -1
        absoluteRow == selection.endRow -> selection.endCol
        else -> columns
    }

    private fun rowRenderHints(
        frame: TerminalFrame,
        selection: TerminalSelection,
        rowIndex: Int
    ): RowRenderHints {
        val absoluteRow = frame.topRow + rowIndex
        val cursor = frame.cursor
        return RowRenderHints(
            selectionStart = if (absoluteRow == selection.startRow) selection.startCol else -1,
            selectionEnd = rowSelectionEnd(absoluteRow, selection, frame.columns),
            cursorX = if (cursor.visible && absoluteRow == cursor.row) cursor.column else -1,
            cursorStyle = cursor.style,
            reverseVideo = frame.reverseVideo
        )
    }

    private fun recordTileLayer(
        drawScope: DrawScope,
        frame: TerminalFrame,
        selection: TerminalSelection,
        work: TerminalRenderTileWork,
        workIndex: Int
    ) {
        val tileIndex = work.tileIndexAt(workIndex)
        val firstRow = work.firstRow(tileIndex)
        val endRowExclusive = work.endRowExclusive(tileIndex)
        val tile = tiles[tileIndex]
        tile.layer.record(
            density = drawScope,
            layoutDirection = drawScope.layoutDirection,
            size = IntSize(width, (endRowExclusive - firstRow) * lineHeight)
        ) {
            drawIntoCanvas { canvas ->
                for (rowIndex in firstRow until endRowExclusive) {
                    if (frame.row(rowIndex) != null) {
                        rowRenderer.renderRow(
                            canvas = canvas.nativeCanvas,
                            frame = frame,
                            rowIndex = rowIndex,
                            hints = rowRenderHints(frame, selection, rowIndex),
                            baselineY = ((rowIndex - firstRow + 1) * lineHeight).toFloat()
                        )
                    }
                }
            }
        }
        for (rowIndex in firstRow until endRowExclusive) {
            val row = frame.row(rowIndex)
            if (row == null) {
                rowStates[rowIndex].clear()
            } else {
                rowStates[rowIndex].applyFrame(
                    row,
                    rowRenderHints(frame, selection, rowIndex),
                    paletteVersion,
                    frame.linkLayout?.rowContentHash(rowIndex) ?: 0L
                )
            }
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
            val visibleTileCount = tilePlanner.tileCount(visibleRowCount)
            for (tileIndex in 0 until visibleTileCount) {
                drawLayer(tiles[tileIndex].layer)
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
    }
}

private class TerminalTileState(
    val layer: GraphicsLayer
)

private fun Color.toArgb(): Int {
    val r = (red * 255f).toInt().coerceIn(0, 255)
    val g = (green * 255f).toInt().coerceIn(0, 255)
    val b = (blue * 255f).toInt().coerceIn(0, 255)
    val a = (alpha * 255f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

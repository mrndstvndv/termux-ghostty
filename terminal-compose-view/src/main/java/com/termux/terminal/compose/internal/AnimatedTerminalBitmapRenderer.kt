package com.termux.terminal.compose.internal

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalSelection

/**
 * Renders animated shader chains through a retained bitmap input.
 *
 * RenderEffect must be recreated after RuntimeShader uniform updates on Android 13-era devices.
 * That allocates native effects every frame, so animated chains use the shader directly as a Paint
 * shader instead. The bitmap input is double-buffered because the previous frame may still be on
 * the GPU when terminal content changes.
 */
internal class AnimatedTerminalBitmapRenderer(
    private val shaders: List<CompiledShader>,
    private val rowRenderer: TerminalRowRenderer
) {
    private data class Buffer(
        val bitmap: Bitmap,
        val shader: BitmapShader
    )

    private val shaderPaint = Paint()
    private var buffers: Array<Buffer> = emptyArray()
    private var activeBufferIndex = -1
    private var pendingBufferIndex = -1
    private var boundBufferIndex = -1
    private var paintShader: RuntimeShader? = null
    private var resolutionWidth = Float.NaN
    private var resolutionHeight = Float.NaN
    private var renderedContentVersion = Int.MIN_VALUE
    private var renderedSelectionY1 = Int.MIN_VALUE
    private var renderedSelectionY2 = Int.MIN_VALUE
    private var renderedSelectionX1 = Int.MIN_VALUE
    private var renderedSelectionX2 = Int.MIN_VALUE
    private var renderedLinkFrameSequence = Long.MIN_VALUE

    fun draw(
        drawScope: DrawScope,
        frame: TerminalFrame,
        contentVersion: Int,
        selection: TerminalSelection,
        timeSeconds: Float
    ) {
        val width = drawScope.size.width.toInt().coerceAtLeast(1)
        val height = drawScope.size.height.toInt().coerceAtLeast(1)
        ensureSize(width, height)

        val bitmapCanvas = beginRenderIfNeeded(contentVersion, selection, frame)
        if (bitmapCanvas != null) {
            rowRenderer.renderAll(
                canvas = bitmapCanvas,
                frame = frame,
                selectionStartRow = selection.startRow,
                selectionEndRow = selection.endRow,
                selectionStartCol = selection.startCol,
                selectionEndCol = selection.endCol,
                reverseVideo = frame.reverseVideo
            )
            finishRender(contentVersion, selection, frame)
        }

        val activeBuffer = buffers[activeBufferIndex]
        val resolutionChanged =
            resolutionWidth != width.toFloat() || resolutionHeight != height.toFloat()
        shaders.forEach { compiledShader ->
            compiledShader.updateUniforms(
                timeSeconds = timeSeconds,
                width = width.toFloat(),
                height = height.toFloat(),
                updateResolution = resolutionChanged
            )
        }
        if (resolutionChanged) {
            resolutionWidth = width.toFloat()
            resolutionHeight = height.toFloat()
        }

        if (boundBufferIndex != activeBufferIndex) {
            bindContentShader(activeBuffer.shader)
            boundBufferIndex = activeBufferIndex
        }

        val finalShader = shaders.last().shader
        if (paintShader !== finalShader) {
            shaderPaint.shader = finalShader
            paintShader = finalShader
        }

        drawScope.drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shaderPaint)
        }
    }

    fun release() {
        shaderPaint.shader = null
        buffers = emptyArray()
        activeBufferIndex = -1
        pendingBufferIndex = -1
        boundBufferIndex = -1
        paintShader = null
    }

    private fun bindContentShader(contentShader: Shader) {
        var inputShader = contentShader
        shaders.forEach { compiledShader ->
            compiledShader.shader.setInputShader("content", inputShader)
            inputShader = compiledShader.shader
        }
    }

    private fun beginRenderIfNeeded(
        contentVersion: Int,
        selection: TerminalSelection,
        frame: TerminalFrame
    ): AndroidCanvas? {
        if (!needsRender(contentVersion, selection, frame)) return null

        pendingBufferIndex = if (activeBufferIndex == -1) {
            0
        } else {
            (activeBufferIndex + 1) % buffers.size
        }
        return AndroidCanvas(buffers[pendingBufferIndex].bitmap)
    }

    private fun finishRender(contentVersion: Int, selection: TerminalSelection, frame: TerminalFrame) {
        if (pendingBufferIndex == -1) return

        activeBufferIndex = pendingBufferIndex
        pendingBufferIndex = -1
        renderedContentVersion = contentVersion
        renderedSelectionY1 = selection.startRow
        renderedSelectionY2 = selection.endRow
        renderedSelectionX1 = selection.startCol
        renderedSelectionX2 = selection.endCol
        renderedLinkFrameSequence = frame.linkLayout?.frameSequence ?: Long.MIN_VALUE
    }

    private fun ensureSize(width: Int, height: Int) {
        val currentWidth = buffers.firstOrNull()?.bitmap?.width ?: 0
        val currentHeight = buffers.firstOrNull()?.bitmap?.height ?: 0
        if (buffers.size == 2 && currentWidth >= width && currentHeight >= height) return

        val bufferWidth = maxOf(width, currentWidth)
        val bufferHeight = maxOf(height, currentHeight)
        buffers = Array(2) {
            val bitmap = Bitmap.createBitmap(bufferWidth, bufferHeight, Bitmap.Config.ARGB_8888)
            Buffer(
                bitmap = bitmap,
                shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            )
        }
        activeBufferIndex = -1
        pendingBufferIndex = -1
        boundBufferIndex = -1
        renderedContentVersion = Int.MIN_VALUE
        renderedSelectionY1 = Int.MIN_VALUE
        renderedSelectionY2 = Int.MIN_VALUE
        renderedSelectionX1 = Int.MIN_VALUE
        renderedSelectionX2 = Int.MIN_VALUE
        renderedLinkFrameSequence = Long.MIN_VALUE
    }

    private fun needsRender(contentVersion: Int, selection: TerminalSelection, frame: TerminalFrame): Boolean {
        val linkFrameSequence = frame.linkLayout?.frameSequence ?: Long.MIN_VALUE
        return activeBufferIndex == -1 ||
            contentVersion != renderedContentVersion ||
            selection.startRow != renderedSelectionY1 ||
            selection.endRow != renderedSelectionY2 ||
            selection.startCol != renderedSelectionX1 ||
            selection.endCol != renderedSelectionX2 ||
            linkFrameSequence != renderedLinkFrameSequence
    }
}

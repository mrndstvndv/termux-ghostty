@file:Suppress("TooManyFunctions")

package com.mrndtvndv.term.ui.workspace

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TerminalConstants
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.sqrt

/** Cursor trails adapted from sahaj-b/ghostty-cursor-shaders (MIT License). */
enum class CursorTrailEffect(val key: String, val label: String) {
    NONE("none", "None"),
    WARP("warp", "Warp"),
    SWEEP("sweep", "Sweep"),
    TAIL("tail", "Tail");

    companion object {
        fun fromPref(value: String?, legacyEnabled: Boolean = false): CursorTrailEffect {
            if (value != null) return entries.firstOrNull { it.key == value } ?: NONE
            return if (legacyEnabled) WARP else NONE
        }
    }
}

internal class RenderSelection(
    var y1: Int = -1,
    var y2: Int = -1,
    var x1: Int = -1,
    var x2: Int = -1
)

/** Cursor positions for the selected trail and the animation-clock time of the last move. */
internal class CursorTrailState {
    var prevCol = -1
    var prevRow = -1
    var currCol = -1
    var currRow = -1
    var changeSeconds = 0f
}

internal class TerminalVisualEffects(
    private val terminalEffect: TerminalEffect,
    private val postShader: RuntimeShader?,
    private val cursorTrailEffect: CursorTrailEffect
) {
    private var animationTimeSeconds by mutableFloatStateOf(0f)
    private val bitmapRenderState = BitmapRenderState()
    private val cursorTrailState = CursorTrailState()

    val isAnimated: Boolean
        get() = (terminalEffect.animated && postShader != null) ||
            cursorTrailEffect != CursorTrailEffect.NONE

    fun updateAnimationTime(timeSeconds: Float) {
        animationTimeSeconds = timeSeconds
    }

    fun draw(
        drawScope: DrawScope,
        snapshot: ScreenSnapshot,
        renderer: TerminalRenderer,
        topRow: Int,
        contentVersion: Int,
        selection: RenderSelection
    ) {
        // Read the animation clock inside the draw scope. With no post shader this read is
        // what makes the frame loop invalidate the canvas — without it the cursor trail
        // freezes as soon as the terminal content stops changing.
        val timeSeconds = animationTimeSeconds
        drawScope.drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            if (postShader == null) {
                renderer.render(
                    snapshot,
                    native,
                    selection.y1,
                    selection.y2,
                    selection.x1,
                    selection.x2
                )
            } else {
                val targetW = drawScope.size.width.toInt().coerceAtLeast(1)
                val targetH = drawScope.size.height.toInt().coerceAtLeast(1)
                val bitmapCanvas = bitmapRenderState.beginRenderIfNeeded(
                    width = targetW,
                    height = targetH,
                    contentVersion = contentVersion,
                    selection = selection
                )
                if (bitmapCanvas != null) {
                    renderer.render(
                        snapshot,
                        bitmapCanvas,
                        selection.y1,
                        selection.y2,
                        selection.x1,
                        selection.x2
                    )
                    bitmapRenderState.finishRender(contentVersion, selection)
                }
                bitmapRenderState.draw(
                    canvas = native,
                    shader = postShader,
                    timeSeconds = timeSeconds,
                    width = drawScope.size.width,
                    height = drawScope.size.height
                )
            }
        }
        if (cursorTrailEffect != CursorTrailEffect.NONE) {
            drawScope.drawCursorTrail(
                effect = cursorTrailEffect,
                snapshot = snapshot,
                state = cursorTrailState,
                renderer = renderer,
                topRow = topRow,
                timeSeconds = timeSeconds
            )
        }
    }
}

@Composable
internal fun rememberTerminalVisualEffects(
    terminalEffect: TerminalEffect,
    cursorTrailEffect: CursorTrailEffect
): TerminalVisualEffects {
    val postShader = rememberRuntimeShader(terminalEffect)
    return remember(terminalEffect, cursorTrailEffect, postShader) {
        TerminalVisualEffects(terminalEffect, postShader, cursorTrailEffect)
    }
}

private class BitmapRenderState {
    private data class Buffer(
        val bitmap: Bitmap,
        val shader: BitmapShader
    )

    private val shaderPaint = AndroidPaint()
    private var boundShader: RuntimeShader? = null
    private var boundBufferIndex = -1
    private var paintShader: RuntimeShader? = null
    private var buffers: Array<Buffer> = emptyArray()
    private var activeBufferIndex = -1
    private var renderedContentVersion = Int.MIN_VALUE
    private var renderedSelectionY1 = Int.MIN_VALUE
    private var renderedSelectionY2 = Int.MIN_VALUE
    private var renderedSelectionX1 = Int.MIN_VALUE
    private var renderedSelectionX2 = Int.MIN_VALUE

    private var pendingBufferIndex = -1

    fun beginRenderIfNeeded(
        width: Int,
        height: Int,
        contentVersion: Int,
        selection: RenderSelection
    ): AndroidCanvas? {
        ensureSize(width, height)
        if (!needsRender(contentVersion, selection)) return null

        pendingBufferIndex = (activeBufferIndex + 1) % buffers.size
        return AndroidCanvas(buffers[pendingBufferIndex].bitmap)
    }

    fun finishRender(contentVersion: Int, selection: RenderSelection) {
        if (pendingBufferIndex == -1) return

        activeBufferIndex = pendingBufferIndex
        pendingBufferIndex = -1
        renderedContentVersion = contentVersion
        renderedSelectionY1 = selection.y1
        renderedSelectionY2 = selection.y2
        renderedSelectionX1 = selection.x1
        renderedSelectionX2 = selection.x2
    }

    fun draw(
        canvas: AndroidCanvas,
        shader: RuntimeShader,
        timeSeconds: Float,
        width: Float,
        height: Float
    ) {
        shader.updateUniforms(timeSeconds, width, height)
        if (boundShader !== shader || boundBufferIndex != activeBufferIndex) {
            shader.setInputShader("content", buffers[activeBufferIndex].shader)
            boundShader = shader
            boundBufferIndex = activeBufferIndex
        }
        if (paintShader !== shader) {
            shaderPaint.shader = shader
            paintShader = shader
        }
        canvas.drawRect(0f, 0f, width, height, shaderPaint)
    }

    private fun ensureSize(width: Int, height: Int) {
        if (buffers.size == 2 && buffers[0].bitmap.width == width && buffers[0].bitmap.height == height) return

        buffers = Array(2) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
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
    }

    private fun needsRender(contentVersion: Int, selection: RenderSelection): Boolean =
        activeBufferIndex == -1 ||
            contentVersion != renderedContentVersion ||
            selection.y1 != renderedSelectionY1 ||
            selection.y2 != renderedSelectionY2 ||
            selection.x1 != renderedSelectionX1 ||
            selection.x2 != renderedSelectionX2
}

// Cursor warp trail — port of ghostty's cursor_warp.glsl shader
private const val WarpDurationSeconds = 0.2f
private const val WarpTrailSize = 0.8f
private const val WarpThicknessY = 1.0f
private const val WarpThicknessX = 0.9f

/** EaseOutCirc, matching the shader's default easing. */
private fun easeOutCirc(x: Float): Float = sqrt(1f - (x - 1f) * (x - 1f))

/** Warp quad corners plus the current cursor rect (for the punch-out hole). */
private class WarpQuad(
    val topLeft: Offset,
    val topRight: Offset,
    val bottomRight: Offset,
    val bottomLeft: Offset,
    val cursorRect: Rect
)

/**
 * Computes the four eased corners of the warp smear between the previous and
 * current cursor rects, plus the current cursor rect for the punch-out hole.
 */
private fun warpQuad(
    state: CursorTrailState,
    topRow: Int,
    fontWidth: Float,
    lineHeight: Float,
    cursorStyle: Int,
    progressSeconds: Float
): WarpQuad {
    // Thickness-scaled rect corners (TRAIL_THICKNESS = 1.0, TRAIL_THICKNESS_X = 0.9)
    fun rectCorners(x: Float, y: Float): List<Offset> {
        val halfWidth = fontWidth * 0.5f * WarpThicknessX
        val halfHeight = lineHeight * 0.5f * WarpThicknessY
        val centerX = x + fontWidth * 0.5f
        val centerY = y + lineHeight * 0.5f
        return listOf(
            Offset(centerX - halfWidth, centerY - halfHeight), // top-left
            Offset(centerX + halfWidth, centerY - halfHeight), // top-right
            Offset(centerX + halfWidth, centerY + halfHeight), // bottom-right
            Offset(centerX - halfWidth, centerY + halfHeight) // bottom-left
        )
    }

    val ccRect = rectCorners(state.currCol * fontWidth, (state.currRow - topRow) * lineHeight)
    val cpRect = rectCorners(state.prevCol * fontWidth, (state.prevRow - topRow) * lineHeight)

    // Per-corner durations from alignment with the move direction
    val signX = if (state.currCol >= state.prevCol) 1f else -1f
    val signY = if (state.currRow >= state.prevRow) 1f else -1f
    val leadDuration = WarpDurationSeconds * (1f - WarpTrailSize)
    val sideDuration = (leadDuration + WarpDurationSeconds) / 2f

    fun durationFromDot(dotValue: Float): Float = when {
        dotValue >= 0.5f -> leadDuration
        dotValue >= -0.5f -> sideDuration
        else -> WarpDurationSeconds
    }

    var tlDuration = durationFromDot(-signX + signY)
    var trDuration = durationFromDot(signX + signY)
    var blDuration = durationFromDot(-signX - signY)
    var brDuration = durationFromDot(signX - signY)

    // Horizontal-rail correction so leading/trailing edges move as rails
    val isMovingRight = signX >= 0.5f
    val isMovingLeft = -signX >= 0.5f
    val leftRailDuration = durationFromDot(-signX)
    val rightRailDuration = durationFromDot(signX)
    if (isMovingLeft) {
        tlDuration = leftRailDuration
        blDuration = leftRailDuration
    }
    if (isMovingRight) {
        trDuration = rightRailDuration
        brDuration = rightRailDuration
    }

    fun easedCorner(progress: Float, from: Offset, to: Offset): Offset {
        val t = easeOutCirc(progress.coerceIn(0f, 1f))
        return Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t)
    }

    return WarpQuad(
        topLeft = easedCorner(progressSeconds / tlDuration, cpRect[0], ccRect[0]),
        topRight = easedCorner(progressSeconds / trDuration, cpRect[1], ccRect[1]),
        bottomRight = easedCorner(progressSeconds / brDuration, cpRect[2], ccRect[2]),
        bottomLeft = easedCorner(progressSeconds / blDuration, cpRect[3], ccRect[3]),
        cursorRect = cursorRectForCell(
            column = state.currCol,
            row = state.currRow,
            topRow = topRow,
            fontWidth = fontWidth,
            lineHeight = lineHeight,
            cursorStyle = cursorStyle
        )
    )
}

/**
 * Cursor trail geometry adapted from cursor_sweep.glsl and cursor_tail.glsl in
 * https://github.com/sahaj-b/ghostty-cursor-shaders (MIT License).
 */
@Suppress("LongMethod", "ReturnCount")
private fun DrawScope.drawCursorTrail(
    effect: CursorTrailEffect,
    snapshot: ScreenSnapshot,
    state: CursorTrailState,
    renderer: TerminalRenderer,
    topRow: Int,
    timeSeconds: Float
) {
    if (!snapshot.isCursorVisible) return

    val col = snapshot.getCursorCol()
    val row = snapshot.getCursorRow()
    if (state.currCol != col || state.currRow != row) {
        state.prevCol = state.currCol
        state.prevRow = state.currRow
        state.currCol = col
        state.currRow = row
        state.changeSeconds = timeSeconds
    }

    if (state.currCol < 0 || state.prevCol < 0) return

    val moveCols = (state.currCol - state.prevCol).toFloat()
    val moveRows = (state.currRow - state.prevRow).toFloat()
    val moveDistance = sqrt(moveCols * moveCols + moveRows * moveRows)
    if (moveDistance < TrailMinDistanceCells) return

    val progressSeconds = timeSeconds - state.changeSeconds
    val fontWidth = renderer.getFontWidth()
    val lineHeight = renderer.getFontLineSpacing().toFloat()
    val cursorStyle = snapshot.getCursorStyle()
    val currentRect = cursorRectForCell(
        column = state.currCol,
        row = state.currRow,
        topRow = topRow,
        fontWidth = fontWidth,
        lineHeight = lineHeight,
        cursorStyle = cursorStyle
    )
    val previousRect = cursorRectForCell(
        column = state.prevCol,
        row = state.prevRow,
        topRow = topRow,
        fontWidth = fontWidth,
        lineHeight = lineHeight,
        cursorStyle = cursorStyle
    )
    val cursorColor = cursorTrailColor(snapshot)

    when (effect) {
        CursorTrailEffect.NONE -> Unit
        CursorTrailEffect.WARP -> {
            if (progressSeconds >= WarpDurationSeconds - 0.001f) return
            drawWarpCursorTrail(
                state = state,
                topRow = topRow,
                fontWidth = fontWidth,
                lineHeight = lineHeight,
                cursorStyle = cursorStyle,
                progressSeconds = progressSeconds,
                cursorColor = cursorColor
            )
        }
        CursorTrailEffect.SWEEP -> {
            if (progressSeconds >= SweepDurationSeconds - 0.001f) return
            drawSweepCursorTrail(
                previousRect = previousRect,
                currentRect = currentRect,
                progressSeconds = progressSeconds,
                cursorColor = cursorColor
            )
        }
        CursorTrailEffect.TAIL -> {
            if (progressSeconds >= TailDurationSeconds - 0.001f) return
            drawTailCursorTrail(
                previousRect = previousRect,
                currentRect = currentRect,
                moveDistance = moveDistance,
                progressSeconds = progressSeconds,
                cursorColor = cursorColor
            )
        }
    }
}

private fun DrawScope.drawWarpCursorTrail(
    state: CursorTrailState,
    topRow: Int,
    fontWidth: Float,
    lineHeight: Float,
    cursorStyle: Int,
    progressSeconds: Float,
    cursorColor: Color
) {
    val quad = warpQuad(
        state = state,
        topRow = topRow,
        fontWidth = fontWidth,
        lineHeight = lineHeight,
        cursorStyle = cursorStyle,
        progressSeconds = progressSeconds
    )
    val quadPath = Path().apply {
        fillType = PathFillType.EvenOdd
        moveTo(quad.topLeft.x, quad.topLeft.y)
        lineTo(quad.topRight.x, quad.topRight.y)
        lineTo(quad.bottomRight.x, quad.bottomRight.y)
        lineTo(quad.bottomLeft.x, quad.bottomLeft.y)
        close()
        addRect(quad.cursorRect)
    }
    drawPath(quadPath, cursorColor)
}

private fun DrawScope.drawSweepCursorTrail(
    previousRect: Rect,
    currentRect: Rect,
    progressSeconds: Float,
    cursorColor: Color
) {
    val shrink = easeOutCubic(progressSeconds / SweepDurationSeconds)
    val tailRect = lerpRect(
        start = currentRect,
        end = previousRect,
        fraction = SweepTrailLength * (1f - shrink)
    )
    drawTrailBetweenRects(
        headRect = currentRect,
        tailRect = tailRect,
        cursorRect = currentRect,
        color = cursorColor
    )
}

private fun DrawScope.drawTailCursorTrail(
    previousRect: Rect,
    currentRect: Rect,
    moveDistance: Float,
    progressSeconds: Float,
    cursorColor: Color
) {
    val progress = (progressSeconds / TailDurationSeconds).coerceIn(0f, 1f)
    val isLongMove = moveDistance >= TailMaxTrailLengthCells
    val tailDelay = (TailMaxTrailLengthCells / moveDistance).coerceIn(0f, 1f)
    val headProgress = if (isLongMove) 1f else easeOutCirc(progress)
    val tailProgress = if (isLongMove) {
        easeOutCirc(progress)
    } else {
        easeOutCirc(smoothStep(tailDelay, 1f, progress))
    }
    val headRect = lerpRect(previousRect, currentRect, headProgress)
    val tailRect = lerpRect(previousRect, currentRect, tailProgress)
    drawTrailBetweenRects(
        headRect = headRect,
        tailRect = tailRect,
        cursorRect = currentRect,
        color = cursorColor
    )
}

private fun DrawScope.drawTrailBetweenRects(
    headRect: Rect,
    tailRect: Rect,
    cursorRect: Rect,
    color: Color
) {
    val trailPath = trailPathBetweenRects(headRect, tailRect).apply {
        fillType = PathFillType.EvenOdd
        addRect(cursorRect)
    }
    drawPath(trailPath, color)
}

private fun trailPathBetweenRects(first: Rect, second: Rect): Path {
    val path = Path()
    val left = minOf(first.left, second.left)
    val top = minOf(first.top, second.top)
    val right = maxOf(first.right, second.right)
    val bottom = maxOf(first.bottom, second.bottom)
    val horizontalDistance = abs(first.center.x - second.center.x)
    val verticalDistance = abs(first.center.y - second.center.y)

    if (horizontalDistance < 0.001f || verticalDistance < 0.001f) {
        path.addRect(Rect(left, top, right, bottom))
        return path
    }

    val hull = convexHull(
        listOf(
            Offset(first.left, first.top),
            Offset(first.right, first.top),
            Offset(first.right, first.bottom),
            Offset(first.left, first.bottom),
            Offset(second.left, second.top),
            Offset(second.right, second.top),
            Offset(second.right, second.bottom),
            Offset(second.left, second.bottom)
        )
    )
    if (hull.isEmpty()) return path

    path.moveTo(hull.first().x, hull.first().y)
    hull.drop(1).forEach { point -> path.lineTo(point.x, point.y) }
    path.close()
    return path
}

private fun convexHull(points: List<Offset>): List<Offset> {
    val sortedPoints = points.sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })
    if (sortedPoints.size <= 1) return sortedPoints

    fun buildHalf(pointsToBuild: List<Offset>): MutableList<Offset> {
        val half = mutableListOf<Offset>()
        pointsToBuild.forEach { point ->
            while (half.size >= 2 && crossProduct(half[half.lastIndex - 1], half.last(), point) <= 0f) {
                half.removeAt(half.lastIndex)
            }
            half.add(point)
        }
        return half
    }

    val lower = buildHalf(sortedPoints)
    val upper = buildHalf(sortedPoints.asReversed())
    lower.removeAt(lower.lastIndex)
    upper.removeAt(upper.lastIndex)
    return lower + upper
}

private fun crossProduct(origin: Offset, first: Offset, second: Offset): Float =
    (first.x - origin.x) * (second.y - origin.y) -
        (first.y - origin.y) * (second.x - origin.x)

private fun lerpRect(start: Rect, end: Rect, fraction: Float): Rect = Rect(
    left = lerpFloat(start.left, end.left, fraction),
    top = lerpFloat(start.top, end.top, fraction),
    right = lerpFloat(start.right, end.right, fraction),
    bottom = lerpFloat(start.bottom, end.bottom, fraction)
)

private fun lerpFloat(start: Float, end: Float, fraction: Float): Float =
    start + (end - start) * fraction

private fun cursorRectForCell(
    column: Int,
    row: Int,
    topRow: Int,
    fontWidth: Float,
    lineHeight: Float,
    cursorStyle: Int
): Rect {
    val left = column * fontWidth
    val top = (row - topRow) * lineHeight
    val right = left + fontWidth
    val bottom = top + lineHeight
    return when (cursorStyle) {
        TerminalConstants.TERMINAL_CURSOR_STYLE_UNDERLINE ->
            Rect(left, bottom - lineHeight / 4f, right, bottom)
        TerminalConstants.TERMINAL_CURSOR_STYLE_BAR ->
            Rect(left, top, left + fontWidth / 4f, bottom)
        else -> Rect(left, top, right, bottom)
    }
}

private fun cursorTrailColor(snapshot: ScreenSnapshot): Color {
    var cursorColor = Color(snapshot.getPaletteColor(TextStyle.COLOR_INDEX_CURSOR))
    val luminance = 0.299f * cursorColor.red + 0.587f * cursorColor.green + 0.114f * cursorColor.blue
    if (luminance < 0.45f) {
        cursorColor = lerp(cursorColor, Color.White, 0.5f)
    }
    return cursorColor
}

private fun smoothStep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge1 <= edge0) return if (value < edge1) 0f else 1f
    val normalized = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return normalized * normalized * (3f - 2f * normalized)
}

private fun easeOutCubic(value: Float): Float {
    val clamped = value.coerceIn(0f, 1f)
    val inverse = 1f - clamped
    return 1f - inverse * inverse * inverse
}

private const val TrailMinDistanceCells = 1.5f
private const val SweepDurationSeconds = 0.2f
private const val SweepTrailLength = 0.5f
private const val TailDurationSeconds = 0.09f
private const val TailMaxTrailLengthCells = 8f

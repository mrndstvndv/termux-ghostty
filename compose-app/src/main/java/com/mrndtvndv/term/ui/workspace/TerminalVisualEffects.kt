@file:Suppress("TooManyFunctions")

package com.mrndtvndv.term.ui.workspace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.GraphicsContext
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalGraphicsContext
import com.termux.terminal.ScreenSnapshot
import com.termux.terminal.TerminalConstants
import com.termux.terminal.TextStyle
import com.termux.view.TerminalRenderer
import com.termux.view.TerminalViewLinkLayout
import kotlin.math.abs
import kotlin.math.sqrt

enum class VisualEffectFrameRate(
    val key: String,
    val label: String,
    val framesPerSecond: Float?
) {
    VSYNC("vsync", "VSync (display rate)", null),
    FPS_30("30", "30 FPS", 30f),
    FPS_60("60", "60 FPS", 60f),
    FPS_90("90", "90 FPS", 90f),
    FPS_120("120", "120 FPS", 120f);

    companion object {
        fun fromPref(value: String?): VisualEffectFrameRate {
            if (value == "display") return VSYNC
            return entries.firstOrNull { it.key == value } ?: VSYNC
        }
    }
}

/** Cursor trails adapted from sahaj-b/ghostty-cursor-shaders (MIT License). */
enum class CursorTrailEffect(val key: String, val label: String) {
    NONE("none", "None"),
    WARP("warp", "Warp"),
    SWEEP("sweep", "Sweep"),
    TAIL("tail", "Tail");

    companion object {
        fun fromPref(value: String?): CursorTrailEffect {
            if (value != null) return entries.firstOrNull { it.key == value } ?: NONE
            // Warp trail on by default for fresh installs.
            return WARP
        }
    }
}

internal class RenderSelection(
    var y1: Int = -1,
    var y2: Int = -1,
    var x1: Int = -1,
    var x2: Int = -1
)

/** Cursor positions for the selected trail and the wall-clock time of the last move. */
internal class CursorTrailState {
    var prevCol = -1
    var prevRow = -1
    var currCol = -1
    var currRow = -1
    var changeNanos = 0L
    var changeSeconds = 0f
}

internal class TerminalVisualEffects(
    private val postShaders: List<CompiledShader>,
    private val cursorTrailEffect: CursorTrailEffect,
    graphicsContext: GraphicsContext
) {
    private var animationTimeSeconds by mutableFloatStateOf(0f)
    private val renderNodeRenderer = TerminalRenderNodeRenderer(
        graphicsContext = graphicsContext,
        shaders = postShaders
    )
    private val cursorTrailState = CursorTrailState()

    val isContinuouslyAnimated: Boolean
        get() = postShaders.any { it.definition.animated }

    val isAnimated: Boolean
        get() = isContinuouslyAnimated || cursorTrailEffect != CursorTrailEffect.NONE

    /**
     * Whether the animation clock must keep ticking (and thus the canvas redrawing).
     * Animated post shaders need every frame; cursor trails only need frames for the
     * duration of the trail after a move, so an idle terminal doesn't redraw forever.
     */
    fun needsFrame(): Boolean {
        if (isContinuouslyAnimated) return true
        if (cursorTrailEffect == CursorTrailEffect.NONE) return false
        val elapsed = (System.nanoTime() - cursorTrailState.changeNanos) / 1_000_000_000f
        return elapsed < MaxTrailDurationSeconds + TrailFrameGraceSeconds
    }

    fun updateAnimationTime(timeSeconds: Float) {
        animationTimeSeconds = timeSeconds
    }

    fun drawTerminal(
        drawScope: DrawScope,
        snapshot: ScreenSnapshot,
        renderer: TerminalRenderer,
        contentVersion: Int,
        selection: RenderSelection,
        linkLayout: TerminalViewLinkLayout?
    ) {
        val timeSeconds = if (isContinuouslyAnimated) {
            animationTimeSeconds
        } else {
            0f
        }
        renderNodeRenderer.draw(
            drawScope = drawScope,
            snapshot = snapshot,
            renderer = renderer,
            contentVersion = contentVersion,
            selection = selection,
            linkLayout = linkLayout,
            timeSeconds = timeSeconds
        )
    }

    fun release() {
        renderNodeRenderer.release()
    }

    fun drawCursorTrail(
        drawScope: DrawScope,
        snapshot: ScreenSnapshot,
        renderer: TerminalRenderer,
        topRow: Int
    ) {
        if (cursorTrailEffect == CursorTrailEffect.NONE) return

        // Keep the animation clock read in the overlay draw scope. The terminal canvas should
        // only be invalidated by terminal content; cursor animation must not rerender every cell.
        val timeSeconds = animationTimeSeconds
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

@Composable
internal fun rememberTerminalVisualEffects(
    shaderDefinitions: List<ShaderDefinition>,
    cursorTrailEffect: CursorTrailEffect
): TerminalVisualEffects {
    val postShaders = rememberRuntimeShaders(shaderDefinitions)
    val graphicsContext = LocalGraphicsContext.current
    val visualEffects = remember(
        shaderDefinitions.map { it.id to it.source },
        cursorTrailEffect,
        postShaders,
        graphicsContext
    ) {
        TerminalVisualEffects(postShaders, cursorTrailEffect, graphicsContext)
    }
    DisposableEffect(visualEffects) {
        onDispose { visualEffects.release() }
    }
    return visualEffects
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
        state.changeNanos = System.nanoTime()
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

/** Extra time the frame loop keeps redrawing after a trail's last visible frame. */
private const val TrailFrameGraceSeconds = 0.05f

/** Longest of the three trail durations — the frame loop must tick at least this long. */
private val MaxTrailDurationSeconds =
    maxOf(WarpDurationSeconds, SweepDurationSeconds, TailDurationSeconds)

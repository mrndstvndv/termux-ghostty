package com.termux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import com.termux.terminal.compose.gpu.GlesTerminalSurface
import com.termux.terminal.compose.gpu.GlesTerminalVisualConfig
import com.termux.terminal.compose.gpu.rememberGlesTerminalSurface
import com.termux.terminal.compose.internal.TerminalController
import java.util.concurrent.atomic.AtomicLong

/** Publishes complete immutable frames to GLES while Compose continues to own interaction. */
@Composable
@Suppress("LongMethod")
internal fun glesTerminalCanvasContent(
    controller: TerminalController,
    metrics: TerminalMetrics,
    selection: TerminalSelection,
    fontSizePx: Float,
    config: TerminalCanvasConfig,
    surfaceKey: Any?,
    modifier: Modifier = Modifier
): GlesTerminalSurface {
    // The surface belongs to the canvas, not to a session: a session switch republishes through
    // the same surface, so its EGL context, glyph atlas and textures survive the switch instead
    // of being torn down and rebuilt per tab.
    val surface = rememberGlesTerminalSurface(surfaceKey = surfaceKey)
    SideEffect {
        surface.initialBackgroundColor = config.initialBackgroundColor
        surface.initialWallpaper = config.wallpaper
    }
    val presentationRevision = remember(surface) { AtomicLong(0L) }
    val publishLatestFrame = {
        if (metrics.viewportWidthPx > 0 && metrics.viewportHeightPx > 0) {
            controller.resizeIfNeeded(metrics.viewportWidthPx, metrics.viewportHeightPx)
            controller.currentFrameForMetrics(metrics)?.let { completeFrame ->
                val timeSeconds = System.nanoTime() / 1_000_000_000f
                surface.publish(
                    frame = completeFrame,
                    metrics = metrics,
                    selection = selection,
                    visualOffsetPx = controller.visualScrollOffsetPx,
                    contentOffsetYPx = config.contentTopInsetPx.toFloat(),
                    cursorEffect = controller.captureCursorEffectSnapshot(
                        frame = completeFrame,
                        timeSeconds = timeSeconds
                    ),
                    contentRevision = completeFrame.sequence,
                    presentationRevision = presentationRevision.incrementAndGet(),
                    visual = GlesTerminalVisualConfig(
                        typeface = config.typeface,
                        fontSizePx = fontSizePx,
                        wallpaper = config.wallpaper,
                        blurRegions = config.blurRegions
                    )
                )
            }
        }
    }
    val currentPublisher by rememberUpdatedState(publishLatestFrame)

    // Tracks whether this surface has already published a frame source, so a controller change
    // restarts the revision domain instead of inheriting the previous session's watermarks.
    val hasPublishedSource = remember(surface) { booleanArrayOf(false) }
    DisposableEffect(surface, controller) {
        if (hasPublishedSource[0]) surface.beginNewSource()
        hasPublishedSource[0] = true
        val callback = { currentPublisher() }
        controller.onFrameAvailable = callback
        callback()
        onDispose {
            if (controller.onFrameAvailable === callback) controller.onFrameAvailable = null
        }
    }
    LaunchedEffect(
        surface,
        controller,
        selection,
        metrics,
        config.contentTopInsetPx,
        fontSizePx,
        config.typeface,
        config.wallpaper,
        config.blurRegions,
        config.cursorEffect
    ) {
        currentPublisher()
    }
    LaunchedEffect(surface, controller, config.cursorEffect) {
        if (config.cursorEffect != null) {
            runGlesCursorEffectFrameLoop(controller, surface)
        }
    }

    GlesTerminalSurface(
        surface = surface,
        modifier = modifier
    )
    return surface
}

/** Pulses the dirty GLES surface only for the published trail's bounded lifetime. */
private suspend fun runGlesCursorEffectFrameLoop(
    controller: TerminalController,
    surface: GlesTerminalSurface
) {
    while (true) {
        controller.awaitInvalidation()
        var timeSeconds: Float
        do {
            timeSeconds = withFrameNanos { nanos -> nanos / 1_000_000_000f }
            surface.requestAnimationFrame(timeSeconds)
        } while (surface.needsCursorAnimationFrame(timeSeconds))
    }
}

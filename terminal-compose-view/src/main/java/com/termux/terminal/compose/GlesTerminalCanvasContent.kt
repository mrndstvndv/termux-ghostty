package com.termux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.termux.terminal.compose.gpu.GlesTerminalSurface
import com.termux.terminal.compose.gpu.GlesTerminalVisualConfig
import com.termux.terminal.compose.gpu.rememberGlesTerminalSurface
import com.termux.terminal.compose.internal.TerminalController
import java.util.concurrent.atomic.AtomicLong

/** Publishes complete immutable frames to GLES while Compose continues to own interaction. */
@Composable
internal fun glesTerminalCanvasContent(
    controller: TerminalController,
    metrics: TerminalMetrics,
    selection: TerminalSelection,
    fontSizePx: Float,
    config: TerminalCanvasConfig,
    modifier: Modifier = Modifier
): GlesTerminalSurface {
    // A controller owns one session/frame sequence. Recreate the surface when
    // that owner changes so a restarted sequence cannot inherit old watermarks.
    val surface = rememberGlesTerminalSurface(surfaceKey = controller)
    val presentationRevision = remember(surface) { AtomicLong(0L) }
    val publishLatestFrame = {
        if (metrics.viewportWidthPx > 0 && metrics.viewportHeightPx > 0) {
            controller.resizeIfNeeded(metrics.viewportWidthPx, metrics.viewportHeightPx)
            controller.currentFrameForMetrics(metrics)?.let { completeFrame ->
                surface.publish(
                    frame = completeFrame,
                    metrics = metrics,
                    selection = selection,
                    contentRevision = completeFrame.sequence,
                    presentationRevision = presentationRevision.incrementAndGet(),
                    visual = GlesTerminalVisualConfig(
                        typeface = config.typeface,
                        fontSizePx = fontSizePx,
                        agslShaders = config.shaders
                    )
                )
            }
        }
    }
    val currentPublisher by rememberUpdatedState(publishLatestFrame)

    DisposableEffect(surface, controller) {
        val callback = { currentPublisher() }
        controller.onFrameAvailable = callback
        onDispose {
            if (controller.onFrameAvailable === callback) controller.onFrameAvailable = null
        }
    }
    LaunchedEffect(
        surface,
        selection,
        metrics,
        fontSizePx,
        config.typeface,
        config.shaders
    ) {
        currentPublisher()
    }

    GlesTerminalSurface(
        surface = surface,
        modifier = modifier
    )
    return surface
}

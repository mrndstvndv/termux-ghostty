package com.mrndtvndv.term.gpu

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.TerminalMetrics
import com.termux.terminal.compose.TerminalSize
import com.termux.terminal.compose.gpu.GlesTerminalDiagnostic
import com.termux.terminal.compose.gpu.GlesTerminalSnapshot
import com.termux.terminal.compose.gpu.GlesTerminalSurface
import com.termux.terminal.compose.gpu.GlesTerminalVisualConfig
import com.termux.terminal.compose.gpu.rememberGlesTerminalSurface

/**
 * The only Ecto call site that depends on the concurrent GPU API.
 *
 * The laboratory deliberately keeps this adapter tiny so the fake backend and
 * scene matrix do not depend on EGL, GL threads, or renderer internals.
 */
@Composable
internal fun GpuRenderingSurface(
    backend: FakeTerminalBackend,
    backendState: GpuLabBackendState,
    frame: TerminalFrame,
    modifier: Modifier,
    atlasResetKey: Long,
    onDiagnostics: (String) -> Unit
) {
    requireGpuLabPublication(backendState, frame)
    val surface = rememberGlesTerminalSurface { diagnostic ->
        onDiagnostics(formatDiagnostics(diagnostic))
    }
    val size = backendState.size
    val metrics = metricsFor(size)

    LaunchedEffect(surface, atlasResetKey) {
        if (atlasResetKey > 0L) surface.requestAtlasReset()
    }

    SideEffect {
        surface.publish(
            GlesTerminalSnapshot(
                frame = frame,
                metrics = metrics,
                selection = backendState.selection,
                viewportWidthPx = size.widthPx,
                viewportHeightPx = size.heightPx,
                contentRevision = frame.sequence,
                presentationRevision = frame.sequence,
                visual = GlesTerminalVisualConfig(
                    typeface = android.graphics.Typeface.MONOSPACE,
                    fontSizePx = metrics.fontSizePx
                )
            )
        )
    }

    GlesTerminalSurface(
        surface = surface,
        modifier = modifier.onSizeChanged { actualSize -> backend.resizeForPixels(actualSize) },
        contentDescription = frame.visibleText()
    )
}

private fun metricsFor(size: TerminalSize): TerminalMetrics = TerminalMetrics.of(
    cellWidthPx = size.cellWidthPx.toFloat(),
    cellHeightPx = size.cellHeightPx.toFloat(),
    ascentPx = (size.cellHeightPx * 3 / 4).toFloat(),
    lineSpacingAndAscentPx = (size.cellHeightPx * 3 / 4).toFloat(),
    viewportWidthPx = size.widthPx,
    viewportHeightPx = size.heightPx,
    fontSizePx = 14f
)

private fun FakeTerminalBackend.resizeForPixels(actualSize: IntSize) {
    if (actualSize.width <= 0 || actualSize.height <= 0) return
    val current = snapshot().size
    resize(
        current.copy(
            widthPx = actualSize.width,
            heightPx = actualSize.height,
            columns = (actualSize.width / current.cellWidthPx).coerceAtLeast(1),
            rows = (actualSize.height / current.cellHeightPx).coerceAtLeast(1)
        )
    )
}

private fun formatDiagnostics(diagnostic: GlesTerminalDiagnostic): String {
    val state = diagnostic.state
    val atlas = state.atlas
    val frame = state.frame
    val kind = when (diagnostic) {
        is GlesTerminalDiagnostic.State -> "state"
        is GlesTerminalDiagnostic.Error -> "error=${diagnostic.stage}: ${diagnostic.message}"
    }
    return buildString {
        append("$kind vendor=${state.vendor} renderer=${state.renderer} ")
        append("GL=${state.version} GLSL=${state.shadingLanguageVersion} ")
        append("EGL generation=${state.generation} ")
        append(
            "atlas=${atlas.pageCount}/${atlas.maxPages} pages " +
                "entries=${atlas.entryCount}/${atlas.maxEntries} " +
                "used=${atlas.usedAreaPx}px resets=${atlas.resetCount} "
        )
        append(
            "frames=${frame.drawCount} skipped=${frame.skippedDrawCount} " +
                "seq=${frame.terminalSequence} presented=${frame.presentationRevision}"
        )
        state.error?.let { append(" lastError=$it") }
    }
}

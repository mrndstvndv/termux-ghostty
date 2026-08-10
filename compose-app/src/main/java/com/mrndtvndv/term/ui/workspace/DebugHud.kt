package com.mrndtvndv.term.ui.workspace

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private const val DefaultRefreshRate = 60f
private const val SampleWindowNanos = 1_000_000_000L
private const val MaxFrameGapNanos = 2_000_000_000L
private const val NanosPerSecond = 1_000_000_000.0

@Composable
fun DebugHud(modifier: Modifier = Modifier) {
    val view = LocalView.current
    val refreshRate = remember(view) {
        view.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRate
    }
    var metrics by remember {
        mutableStateOf(DebugHudMetrics(ramMegabytes = readProcessRamMegabytes()))
    }

    LaunchedEffect(view, refreshRate) {
        val accumulator = FrameMetricsAccumulator(refreshRate)
        while (true) {
            val sample = withFrameNanos { frameTimeNanos ->
                accumulator.record(frameTimeNanos)
            }
            if (sample != null) {
                metrics = metrics.copy(
                    framesPerSecond = sample.framesPerSecond,
                    missedFrames = sample.missedFrames
                )
            }
        }
    }

    // Resource sampling stays off the frame path: these calls are not free and
    // would perturb the very FPS this HUD measures.
    LaunchedEffect(Unit) {
        var previousCpuSample = readProcessCpuSample()
        while (true) {
            delay(1_000)
            val currentCpuSample = readProcessCpuSample()
            metrics = metrics.copy(
                cpuPercent = calculateProcessCpuPercent(previousCpuSample, currentCpuSample),
                ramMegabytes = readProcessRamMegabytes()
            )
            previousCpuSample = currentCpuSample
        }
    }

    Surface(
        modifier = modifier
            .statusBarsPadding()
            .padding(8.dp),
        shape = MaterialTheme.shapes.small,
        color = Color.Black.copy(alpha = 0.78f),
        contentColor = Color.White,
        tonalElevation = 4.dp
    ) {
        DebugHudContent(metrics)
    }
}

@Composable
private fun DebugHudContent(metrics: DebugHudMetrics) {
    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
            text = "DEBUG HUD",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            text = "FPS ${formatFps(metrics.framesPerSecond)}",
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = "CPU ${formatPercent(metrics.cpuPercent)}",
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = "RAM ${metrics.ramMegabytes} MB",
            style = MaterialTheme.typography.labelMedium
        )
        Text(
            text = "MISSED ${metrics.missedFrames}",
            style = MaterialTheme.typography.labelMedium
        )
    }
}

private data class DebugHudMetrics(
    val framesPerSecond: Float = 0f,
    val cpuPercent: Float = 0f,
    val ramMegabytes: Int = 0,
    val missedFrames: Int = 0
)

private data class ProcessCpuSample(
    val processCpuTimeMillis: Long,
    val elapsedRealtimeMillis: Long
)

private class FrameMetricsAccumulator(refreshRate: Float) {
    private val frameIntervalNanos = (NanosPerSecond / refreshRate.coerceAtLeast(1f)).toLong()
    private var previousFrameNanos = 0L
    private var sampleStartNanos = 0L
    private var renderedFrames = 0
    private var missedFrames = 0

    fun record(frameTimeNanos: Long): DebugHudMetrics? {
        if (previousFrameNanos == 0L) {
            resetSample(frameTimeNanos)
            return null
        }

        val frameGapNanos = frameTimeNanos - previousFrameNanos
        previousFrameNanos = frameTimeNanos
        if (frameGapNanos <= 0L || frameGapNanos > MaxFrameGapNanos) {
            resetSample(frameTimeNanos)
            return null
        }

        return recordFrame(frameTimeNanos, frameGapNanos)
    }

    private fun recordFrame(frameTimeNanos: Long, frameGapNanos: Long): DebugHudMetrics? {
        renderedFrames++
        missedFrames += missedFramesIn(frameGapNanos)
        val sampleDurationNanos = frameTimeNanos - sampleStartNanos
        if (sampleDurationNanos < SampleWindowNanos) return null

        val framesPerSecond = renderedFrames * NanosPerSecond / sampleDurationNanos
        val sample = DebugHudMetrics(
            framesPerSecond = framesPerSecond.toFloat(),
            missedFrames = missedFrames
        )
        resetSample(frameTimeNanos)
        return sample
    }

    private fun missedFramesIn(frameGapNanos: Long): Int {
        val expectedFrames = (frameGapNanos.toDouble() / frameIntervalNanos).roundToInt()
        return (expectedFrames - 1).coerceAtLeast(0)
    }

    private fun resetSample(frameTimeNanos: Long) {
        previousFrameNanos = frameTimeNanos
        sampleStartNanos = frameTimeNanos
        renderedFrames = 0
        missedFrames = 0
    }
}

private fun readProcessCpuSample(): ProcessCpuSample = ProcessCpuSample(
    processCpuTimeMillis = Process.getElapsedCpuTime(),
    elapsedRealtimeMillis = SystemClock.elapsedRealtime()
)

private fun calculateProcessCpuPercent(
    previous: ProcessCpuSample,
    current: ProcessCpuSample
): Float {
    val elapsedRealtimeMillis = current.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
    if (elapsedRealtimeMillis <= 0L) return 0f

    val processCpuTimeMillis = current.processCpuTimeMillis - previous.processCpuTimeMillis
    if (processCpuTimeMillis < 0L) return 0f

    return (processCpuTimeMillis.toDouble() * 100 / elapsedRealtimeMillis).toFloat()
}

private fun readProcessRamMegabytes(): Int {
    val memoryInfo = Debug.MemoryInfo()
    Debug.getMemoryInfo(memoryInfo)
    return memoryInfo.totalPss / 1024
}

private fun formatFps(framesPerSecond: Float): String =
    String.format(Locale.US, "%.1f", framesPerSecond)

private fun formatPercent(percent: Float): String =
    String.format(Locale.US, "%.1f%%", percent)

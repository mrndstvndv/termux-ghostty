package com.mrndtvndv.term.ui.workspace

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val DefaultRefreshRate = 60f
private const val SampleWindowNanos = 1_000_000_000L
private const val NanosPerSecond = 1_000_000_000.0

@Composable
fun DebugHud(modifier: Modifier = Modifier) {
    val view = LocalView.current
    val refreshRate = remember(view) {
        view.display?.refreshRate?.takeIf { it > 0f } ?: DefaultRefreshRate
    }
    var metrics by remember {
        mutableStateOf(DebugHudMetrics())
    }

    val frameTracker = remember(view, refreshRate) { FrameMetricsAccumulator(refreshRate) }
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity, frameTracker) {
        val window = activity?.window
            ?: return@DisposableEffect onDispose { }
        val listener = Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
            frameTracker.recordPresentedFrame(
                frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
            )
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(Looper.getMainLooper()))
        onDispose { window.removeOnFrameMetricsAvailableListener(listener) }
    }

    // Resource sampling stays off the frame path: these calls are not free and
    // would perturb the very FPS this HUD measures.
    LaunchedEffect(frameTracker) {
        var previousCpuSample = readProcessCpuSample()
        while (isActive) {
            delay(1_000)
            val currentCpuSample = readProcessCpuSample()
            val frameSample = frameTracker.snapshot()
            val processMetrics = withContext(Dispatchers.IO) {
                DebugHudMetrics(
                    cpuPercent = calculateProcessCpuPercent(previousCpuSample, currentCpuSample),
                    ramMegabytes = readProcessRamMegabytes()
                )
            }
            metrics = metrics.copy(
                framesPerSecond = frameSample.framesPerSecond,
                missedFrames = frameSample.missedFrames,
                cpuPercent = processMetrics.cpuPercent,
                ramMegabytes = processMetrics.ramMegabytes
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

internal data class DebugHudMetrics(
    val framesPerSecond: Float = 0f,
    val cpuPercent: Float = 0f,
    val ramMegabytes: Int = 0,
    val missedFrames: Int = 0
)

private data class ProcessCpuSample(
    val processCpuTimeMillis: Long,
    val elapsedRealtimeMillis: Long
)

internal class FrameMetricsAccumulator(refreshRate: Float) {
    private val frameIntervalNanos =
        (NanosPerSecond / refreshRate.coerceAtLeast(1f)).roundToInt().toLong().coerceAtLeast(1L)
    private var lastSampleNanos: Long? = null
    private var renderedFrames = 0
    private var missedFrames = 0

    fun recordPresentedFrame(frameDurationNanos: Long) {
        if (frameDurationNanos <= 0L) return
        renderedFrames++
        missedFrames += missedFramesIn(frameDurationNanos)
    }

    fun snapshot(nowNanos: Long = System.nanoTime()): DebugHudMetrics {
        val previousSampleNanos = lastSampleNanos
        lastSampleNanos = nowNanos
        val sampleDurationNanos = (previousSampleNanos?.let { nowNanos - it } ?: SampleWindowNanos)
            .coerceAtLeast(1L)
        val sample = DebugHudMetrics(
            framesPerSecond = (renderedFrames * NanosPerSecond / sampleDurationNanos).toFloat(),
            missedFrames = missedFrames
        )
        renderedFrames = 0
        missedFrames = 0
        return sample
    }

    private fun missedFramesIn(frameGapNanos: Long): Int {
        val expectedFrames = (frameGapNanos.toDouble() / frameIntervalNanos).roundToInt()
        return (expectedFrames - 1).coerceAtLeast(0)
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (true) {
        if (current is Activity) return current
        if (current !is ContextWrapper) return null
        val baseContext = current.baseContext
        if (baseContext === current) return null
        current = baseContext
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

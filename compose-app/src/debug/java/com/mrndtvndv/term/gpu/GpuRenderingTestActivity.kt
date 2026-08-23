package com.mrndtvndv.term.gpu

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * Explicit debug-only entry point for deterministic GLES renderer validation.
 * Launch with `adb shell am start -n com.mrndtvndv.term/.gpu.GpuRenderingTestActivity`.
 */
class GpuRenderingTestActivity : ComponentActivity() {

    companion object {
        const val ADB_COMPONENT = "com.mrndtvndv.term/.gpu.GpuRenderingTestActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GpuRenderingLab() }
    }
}

@Composable
@Suppress("LongMethod")
private fun GpuRenderingLab() {
    val backend = remember { FakeTerminalBackend() }
    var backendState by remember { mutableStateOf(backend.snapshot()) }
    var paused by remember { mutableStateOf(false) }
    var animating by remember { mutableStateOf(false) }
    var atlasResetKey by remember { mutableLongStateOf(0L) }
    var surfaceGeneration by remember { mutableIntStateOf(0) }
    var glDiagnostics by remember { mutableStateOf("waiting for GLES 3.0 diagnostics") }
    var lastRendererError by remember { mutableStateOf("none reported") }

    DisposableEffect(backend) {
        backend.observe { backendState = backend.snapshot() }
        onDispose {
            backend.observe(null)
            backend.release()
        }
    }

    LaunchedEffect(backend, animating) {
        if (!animating) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            backend.step()
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0D1117)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(8.dp)
            ) {
                Text(
                    text = "Ecto GLES terminal rendering laboratory",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFE6EDF3)
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    LabButton(if (paused) "Resume surface" else "Pause surface") { paused = !paused }
                    LabButton(if (animating) "Stop animate" else "Animate") { animating = !animating }
                    LabButton("Previous") { backend.previousScene() }
                    LabButton("Next") { backend.nextScene() }
                    LabButton("Step") { backend.step() }
                    LabButton("Force atlas reset") { atlasResetKey++ }
                    LabButton("Recreate surface") {
                        surfaceGeneration++
                        backend.refresh()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Force atlas reset is a debug-only renderer control; reset count appears in GL diagnostics",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFFD166)
                )
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    key(surfaceGeneration, paused) {
                        if (paused) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF161B22)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "GL surface paused/disposed; latest sequence ${backendState.sequence} " +
                                        "will be restored on resume",
                                    modifier = Modifier.padding(16.dp),
                                    color = Color(0xFFFFD166)
                                )
                            }
                        } else {
                            val frame = backend.currentFrame()
                            if (frame == null) {
                                Text(
                                    text = "No immutable frame is available",
                                    color = Color(0xFFFF7B72)
                                )
                            } else {
                                GpuRenderingSurface(
                                    backend = backend,
                                    backendState = backendState,
                                    frame = frame,
                                    modifier = Modifier.fillMaxSize(),
                                    atlasResetKey = atlasResetKey,
                                    onDiagnostics = { diagnostics ->
                                        glDiagnostics = diagnostics
                                        if (diagnostics.contains("error", ignoreCase = true)) {
                                            lastRendererError = diagnostics
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                LabStatusPanel(
                    state = backendState,
                    paused = paused,
                    animating = animating,
                    atlasResetKey = atlasResetKey,
                    surfaceGeneration = surfaceGeneration,
                    glDiagnostics = glDiagnostics,
                    lastRendererError = lastRendererError
                )
            }
        }
    }
}

@Composable
private fun LabButton(label: String, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.sizeIn(minWidth = 72.dp)) {
        Text(label)
    }
}

@Composable
private fun LabStatusPanel(
    state: GpuLabBackendState,
    paused: Boolean,
    animating: Boolean,
    atlasResetKey: Long,
    surfaceGeneration: Int,
    glDiagnostics: String,
    lastRendererError: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF161B22), RoundedCornerShape(4.dp))
            .padding(8.dp)
    ) {
        Text(
            text = "Scene: ${state.sceneTitle} (${state.sceneId})",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFE6EDF3)
        )
        Text(
            text = "Revision=${state.frameIndex} sequence=${state.sequence} " +
                "grid=${state.size.columns}x${state.size.rows} topRow=${state.topRow}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8B949E)
        )
        Text(
            text = "Pixels=${state.size.widthPx}x${state.size.heightPx} " +
                "cell=${state.size.cellWidthPx}x${state.size.cellHeightPx} " +
                "contentTop=${state.size.contentTopPx} " +
                "checksum=${state.checksum} expected(final frame)=${state.expectedChecksum}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8B949E)
        )
        Text(
            text = "Sentinel: ${state.sentinel}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF7EE787)
        )
        Text(
            text = "Selection: ${state.selection.startCol},${state.selection.startRow} -> " +
                "${state.selection.endCol},${state.selection.endRow}",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF7EE787)
        )
        Text(
            text = "Controls: paused=$paused animating=$animating " +
                "atlasResetKey=$atlasResetKey surfaceGeneration=$surfaceGeneration",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF8B949E)
        )
        Text(
            text = "GL vendor/renderer/version + EGL generation + atlas stats: $glDiagnostics",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF79C0FF)
        )
        Text(
            text = "Last renderer error: $lastRendererError",
            style = MaterialTheme.typography.labelSmall,
            color = if (lastRendererError == "none reported") Color(0xFF8B949E) else Color(0xFFFF7B72)
        )
        Text(
            text = "Expected invariants:",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFFFD166)
        )
        state.expectedInvariants.forEach { invariant ->
            Text(
                text = "• $invariant",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF8B949E)
            )
        }
    }
}

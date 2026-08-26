package com.termux.terminal.compose

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import com.termux.terminal.TextStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TerminalCanvasFramePublicationTest {
    @get:Rule
    val compose = createComposeRule()

    private var composeView: View? = null

    @Test
    fun idleFramePublicationUpdatesSemanticsAndGlesPixels() {
        val backend = PublishingBackend(frame(sequence = 1, secondRow = "BEFORE"))
        compose.setContent {
            val currentView = LocalView.current
            SideEffect { composeView = currentView }
            TerminalCanvas(
                backend = backend,
                modifierKeys = ModifierKeyReader.NONE,
                config = TerminalCanvasConfig(
                    fontSize = 24,
                    accessibilityEnabled = true
                ),
                modifier = Modifier.size(width = 320.dp, height = 160.dp)
            )
        }
        compose.waitForIdle()

        compose.onNode(hasText("BEFORE", substring = true), useUnmergedTree = true).assertExists()
        val surfaceView = findSurfaceView()
        val before = captureSurfaceHash(surfaceView)

        compose.runOnIdle {
            backend.publish(frame(sequence = 2, secondRow = "AFTER"))
        }
        compose.waitForIdle()

        compose.onNode(hasText("AFTER", substring = true), useUnmergedTree = true).assertExists()
        val after = captureSurfaceHash(surfaceView, differentFrom = before)
        assertNotEquals(before, after)
    }

    private fun findSurfaceView(): SurfaceView {
        var surfaceView: SurfaceView? = null
        compose.runOnIdle {
            surfaceView = findSurfaceView(checkNotNull(composeView))
        }
        return checkNotNull(surfaceView)
    }

    private fun captureSurfaceHash(surfaceView: SurfaceView, differentFrom: Int? = null): Int {
        assertTrue(surfaceView.width > 0)
        assertTrue(surfaceView.height > 0)
        var lastResult = PixelCopy.ERROR_UNKNOWN
        var lastHash: Int? = null
        repeat(20) {
            val bitmap = Bitmap.createBitmap(
                surfaceView.width,
                surfaceView.height,
                Bitmap.Config.ARGB_8888
            )
            val result = AtomicInteger(PixelCopy.ERROR_UNKNOWN)
            val completed = CountDownLatch(1)
            PixelCopy.request(
                surfaceView,
                bitmap,
                { copyResult ->
                    result.set(copyResult)
                    completed.countDown()
                },
                Handler(Looper.getMainLooper())
            )
            assertTrue(completed.await(2, TimeUnit.SECONDS))
            lastResult = result.get()
            if (lastResult == PixelCopy.SUCCESS) {
                lastHash = pixelHash(bitmap)
                bitmap.recycle()
                if (differentFrom == null || lastHash != differentFrom) {
                    return checkNotNull(lastHash)
                }
            } else {
                bitmap.recycle()
            }
            Thread.sleep(50)
        }
        assertEquals(PixelCopy.SUCCESS, lastResult)
        if (differentFrom != null) assertNotEquals(differentFrom, lastHash)
        return checkNotNull(lastHash)
    }

    private fun pixelHash(bitmap: Bitmap): Int {
        var hash = 1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                hash = 31 * hash + bitmap.getPixel(x, y)
            }
        }
        return hash
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view !is ViewGroup) return null
        for (index in 0 until view.childCount) {
            findSurfaceView(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun frame(sequence: Long, secondRow: String): TerminalFrame {
        val rows = listOf(row("STATIC"), row(secondRow), row("STATIC"), row("STATIC"))
        val colors = IntArray(TextStyle.NUM_INDEXED_COLORS).apply {
            this[TerminalPalette.COLOR_INDEX_FOREGROUND] = 0xFFFFFFFF.toInt()
            this[TerminalPalette.COLOR_INDEX_BACKGROUND] = 0xFF000000.toInt()
            this[TerminalPalette.COLOR_INDEX_CURSOR] = 0xFFFFFFFF.toInt()
        }
        return TerminalFrame(
            sequence = sequence,
            viewport = TerminalViewport(topRow = 0, rows = rows.size, columns = Columns, transcriptRows = 0),
            cursor = TerminalCursor(column = -1, row = -1, visible = false, style = TerminalCursor.STYLE_BLOCK),
            modes = TerminalModes(false, false, false, false, false),
            palette = TerminalPalette.of(colors),
            rows = rows,
            linkLayout = null
        )
    }

    private fun row(text: String): TerminalRow {
        val characters = text.toCharArray()
        val normalStyle =
            (TerminalPalette.COLOR_INDEX_FOREGROUND.toLong() shl 40) or
                (TerminalPalette.COLOR_INDEX_BACKGROUND.toLong() shl 16)
        return TerminalRow(
            columns = Columns,
            text = characters,
            charsUsed = characters.size,
            styles = LongArray(Columns) { normalStyle },
            contentHash = text.hashCode().toLong(),
            cellLayout = null,
            isLineWrap = false
        )
    }

    private class PublishingBackend(initialFrame: TerminalFrame) : TerminalBackend {
        private var listener: TerminalBackendListener? = null
        private var frame = initialFrame

        override fun attach(listener: TerminalBackendListener) {
            this.listener = listener
        }

        override fun detach() {
            listener = null
        }

        override fun refresh() = listener?.onFrameInvalidated() ?: Unit

        override fun resize(size: TerminalSize) = Unit

        override fun submit(command: TerminalCommand): TerminalCommandResult = TerminalCommandResult.Success

        override fun currentFrame(): TerminalFrame = frame

        override fun release() = Unit

        fun publish(nextFrame: TerminalFrame) {
            frame = nextFrame
            listener?.onFrameInvalidated()
        }
    }

    private companion object {
        const val Columns = 20
    }
}

package com.termux.terminal.compose

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.termux.terminal.TextStyle
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class TerminalCanvasFramePublicationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun idleFramePublicationUpdatesSemanticsAndRetainedTilePixels() {
        val backend = PublishingBackend(frame(sequence = 1, secondRow = "BEFORE"))
        compose.setContent {
            TerminalCanvas(
                backend = backend,
                modifierKeys = ModifierKeyReader.NONE,
                config = TerminalCanvasConfig(
                    fontSize = 24,
                    accessibilityEnabled = true
                ),
                modifier = Modifier
                    .size(width = 320.dp, height = 160.dp)
                    .testTag(TerminalTag)
            )
        }
        compose.waitForIdle()

        compose.onNode(hasText("BEFORE", substring = true), useUnmergedTree = true).assertExists()
        val before = compose.onNodeWithTag(TerminalTag).captureToImage()

        compose.runOnIdle {
            backend.publish(frame(sequence = 2, secondRow = "AFTER"))
        }
        compose.waitForIdle()

        compose.onNode(hasText("AFTER", substring = true), useUnmergedTree = true).assertExists()
        val after = compose.onNodeWithTag(TerminalTag).captureToImage()
        assertNotEquals(pixelHash(before), pixelHash(after))
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

    private fun pixelHash(image: ImageBitmap): Int {
        val pixels = image.toPixelMap()
        var hash = 1
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                hash = 31 * hash + pixels[x, y].hashCode()
            }
        }
        return hash
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
        const val TerminalTag = "terminal"
    }
}

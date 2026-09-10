package com.termux.terminal.compose.internal

import android.content.ClipData
import android.content.ClipDescription
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.test.filters.SdkSuppress
import androidx.test.runner.AndroidJUnit4
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImeInputConnectionInstrumentationTest {
    @Test
    fun batchedSoftEnterReachesTerminalAsCarriageReturn() {
        val commands = CopyOnWriteArrayList<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))
        val connection = TerminalInputConnection(
            View(InstrumentationRegistry.getInstrumentation().targetContext),
            processor::process
        )

        assertTrue(connection.beginBatchEdit())
        assertTrue(connection.commitText("\n", 1))
        assertTrue(commands.isEmpty())
        connection.endBatchEdit()

        Log.i(TAG, "IME_ENTER_COMMANDS=${commands.toList()}")
        assertEquals(
            listOf(TerminalCommand.Key(keyCode = 0, metaState = 0, down = true, codePoint = '\r'.code)),
            commands.toList()
        )
        connection.closeConnection()
    }

    @Test
    fun terminalWithoutContentHandlerDoesNotAdvertiseImagePaste() {
        val editorInfo = EditorInfo()

        editorInfo.configureForTerminal(acceptsCommitContent = false)

        assertFalse(EditorInfoCompat.getContentMimeTypes(editorInfo).contains("image/*"))
    }

    @SdkSuppress(minSdkVersion = 25)
    @Test
    fun committedImageContentReachesTheHostAsClipData() {
        val received = mutableListOf<ClipData>()
        val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val editorInfo = EditorInfo().apply {
            EditorInfoCompat.setContentMimeTypes(this, arrayOf("image/*"))
        }
        val baseConnection = TerminalInputConnection(view, {})
        @Suppress("DEPRECATION")
        val connection = InputConnectionCompat.createWrapper(
            baseConnection,
            editorInfo,
            InputConnectionCompat.OnCommitContentListener { contentInfo, flags, _ ->
                dispatchCommittedContent(contentInfo, flags) {
                    received += it
                    true
                }
            },
        )
        val uri = Uri.parse("content://keyboard/image.png")

        assertTrue(
            connection.commitContent(
                InputContentInfo(uri, ClipDescription("image", arrayOf("image/png")), null),
                0,
                null,
            )
        )
        assertEquals(uri, received.single().getItemAt(0).uri)
        connection.closeConnection()
    }

    @SdkSuppress(minSdkVersion = 25)
    @Test
    fun committedNonImageContentIsRejected() {
        val view = View(InstrumentationRegistry.getInstrumentation().targetContext)
        val editorInfo = EditorInfo().apply {
            EditorInfoCompat.setContentMimeTypes(this, arrayOf("image/*"))
        }
        val baseConnection = TerminalInputConnection(view, {})
        @Suppress("DEPRECATION")
        val connection = InputConnectionCompat.createWrapper(
            baseConnection,
            editorInfo,
            InputConnectionCompat.OnCommitContentListener { contentInfo, flags, _ ->
                dispatchCommittedContent(contentInfo, flags) { true }
            },
        )

        assertFalse(
            connection.commitContent(
                InputContentInfo(
                    Uri.parse("content://keyboard/text.txt"),
                    ClipDescription("text", arrayOf("text/plain")),
                    null,
                ),
                0,
                null,
            )
        )
        connection.closeConnection()
    }

    private companion object {
        const val TAG = "ImeInputConnectionTest"
    }
}

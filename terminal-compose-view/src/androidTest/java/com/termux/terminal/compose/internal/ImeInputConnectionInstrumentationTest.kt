package com.termux.terminal.compose.internal

import android.util.Log
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
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

    private companion object {
        const val TAG = "ImeInputConnectionTest"
    }
}

package com.termux.terminal.compose.internal

import androidx.compose.ui.text.input.CommitTextCommand
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeInputPipelineTest {
    @Test
    fun directImeCommitUsesOneTextCommand() {
        val commands = mutableListOf<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))

        processor.process(listOf(CommitTextCommand("hello", 5)))

        assertEquals(listOf(TerminalCommand.Text("hello")), commands)
    }

    @Test
    fun singleImeCharacterKeepsTheLowAllocationCodePointPath() {
        val commands = mutableListOf<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))

        processor.process(listOf(CommitTextCommand("h", 1)))

        assertEquals(
            listOf(TerminalCommand.Key(keyCode = 0, metaState = 0, down = true, codePoint = 'h'.code)),
            commands
        )
    }

    @Test
    fun imeDispatchClearsOneShotModifiersAfterSubmission() {
        var clearCount = 0
        val modifiers = object : ModifierKeyReader {
            override fun readControl() = true
            override fun readAlt() = false
            override fun readShift() = false
            override fun readFn() = false
            override fun clearConsumedModifiers() {
                clearCount++
            }
        }
        val translator = TerminalInputTranslator(modifiers) {
            TerminalCommandResult.Success
        }

        translator.sendText("x")

        assertEquals(1, clearCount)
    }
}

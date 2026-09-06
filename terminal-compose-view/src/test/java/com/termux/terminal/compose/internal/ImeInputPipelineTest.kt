package com.termux.terminal.compose.internal

import androidx.compose.ui.text.input.CommitTextCommand
import com.termux.terminal.compose.ModifierKeyReader
import com.termux.terminal.compose.TerminalCommand
import com.termux.terminal.compose.TerminalCommandResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

        processor.process(listOf(CommitTextCommand("hello", 1)))

        assertEquals(listOf(TerminalCommand.Text("hello")), commands)
    }

    @Test
    fun bulkImeCommitRemainsOneBackendTextCommand() {
        val commands = mutableListOf<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))
        val text = "x".repeat(1024)

        processor.process(listOf(CommitTextCommand(text, 1)))

        assertEquals(listOf(TerminalCommand.Text(text)), commands)
    }

    @Test
    fun cursorMovementUsesOneBackendCommand() {
        val commands = mutableListOf<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))

        processor.process(listOf(CommitTextCommand("hello", 1)))
        processor.process(listOf(androidx.compose.ui.text.input.SetSelectionCommand(0, 0)))

        assertEquals(
            listOf(TerminalCommand.Text("hello"), TerminalCommand.CursorMove(-5)),
            commands
        )
    }

    @Test
    fun imeEnterUsesCarriageReturnForRawModeApplications() {
        val commands = mutableListOf<TerminalCommand>()
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) { command ->
            commands += command
            TerminalCommandResult.Success
        }
        val processor = ImeEditCommandProcessor(CommandTerminalInput(translator))

        processor.process(listOf(CommitTextCommand("\n", 1)))

        assertEquals(
            listOf(TerminalCommand.Key(keyCode = 0, metaState = 0, down = true, codePoint = '\r'.code)),
            commands
        )
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
    fun hostCodePointPolicySeesRawInputBeforeControlMapping() {
        val commands = mutableListOf<TerminalCommand>()
        var observedCodePoint = 0
        var observedControl = false
        var observedAlt = false
        val translator = TerminalInputTranslator(
            modifierKeys = ModifierKeyReader.NONE,
            onCodePoint = { codePoint, controlDown, altDown ->
                observedCodePoint = codePoint
                observedControl = controlDown
                observedAlt = altDown
                false
            }
        ) { command ->
            commands += command
            TerminalCommandResult.Success
        }

        translator.sendCodePoint('a'.code, alt = false, controlHeld = true)

        assertEquals('a'.code, observedCodePoint)
        assertEquals(true, observedControl)
        assertEquals(false, observedAlt)
        assertEquals(
            listOf(TerminalCommand.Key(keyCode = 0, metaState = 0, down = true, codePoint = 1)),
            commands
        )
    }

    @Test
    fun codePointPolicyCanBeUpdatedWithoutReplacingTheTranslator() {
        var handled = false
        val translator = TerminalInputTranslator(ModifierKeyReader.NONE) {
            error("The updated host callback should consume the code point")
        }

        translator.updateOnCodePoint { _, _, _ ->
            handled = true
            true
        }
        translator.sendCodePoint('a'.code, alt = false)

        assertTrue(handled)
    }

    @Test
    fun imeTextHonorsStickyShiftModifier() {
        val commands = mutableListOf<TerminalCommand>()
        val modifiers = object : ModifierKeyReader {
            override fun readControl() = false
            override fun readAlt() = false
            override fun readShift() = true
            override fun readFn() = false
        }
        val translator = TerminalInputTranslator(modifiers) { command ->
            commands += command
            TerminalCommandResult.Success
        }

        translator.sendText("a")

        assertEquals(
            listOf(TerminalCommand.Key(keyCode = 0, metaState = 0, down = true, codePoint = 'A'.code)),
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

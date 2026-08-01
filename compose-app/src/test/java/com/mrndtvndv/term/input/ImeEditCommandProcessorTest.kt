package com.mrndtvndv.term.input

import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeEditCommandProcessorTest {

    private class RecordingTerminalInput : ImeEditCommandProcessor.TerminalInput {
        enum class Op { INPUT, DELETE, MOVE_LEFT, MOVE_RIGHT }

        data class Event(val op: Op, val amount: Int = 0)

        val events = mutableListOf<Event>()

        override fun inputCodePoint(codePoint: Int) {
            events += Event(Op.INPUT, codePoint)
        }

        override fun delete() {
            events += Event(Op.DELETE)
        }

        override fun moveCursor(delta: Int) {
            events += Event(if (delta < 0) Op.MOVE_LEFT else Op.MOVE_RIGHT, kotlin.math.abs(delta))
        }

        /** Simulated terminal buffer: INPUT appends, DELETE removes the last char. */
        fun netText(): String {
            val buffer = StringBuilder()
            for (event in events) {
                when (event.op) {
                    Op.INPUT -> buffer.append(event.amount.toChar())
                    Op.DELETE -> if (buffer.isNotEmpty()) buffer.setLength(buffer.length - 1)
                    Op.MOVE_LEFT, Op.MOVE_RIGHT -> { }
                }
            }
            return buffer.toString()
        }

        fun deleteCount(): Int = events.count { it.op == Op.DELETE }
    }

    private fun run(vararg commands: EditCommand): RecordingTerminalInput {
        val terminal = RecordingTerminalInput()
        ImeEditCommandProcessor(terminal).process(commands.toList())
        return terminal
    }

    // The reported bug: "foo" + "." + "b" re-added the previous word because Gboard
    // re-sends the whole region ("foo.b") as composing text after the period commit.
    @Test
    fun periodThenNextCharacterRecomposesOnlyTheSuffix() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo.", -1),
            SetComposingTextCommand("foo.b", 1)
        )
        assertEquals("foo.b", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // Same bug, second signature: the re-send arrives as a commit instead of composing text.
    @Test
    fun periodThenNextCharacterCommitRecomposesOnlyTheSuffix() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            FinishComposingTextCommand(),
            CommitTextCommand("foo.", -1),
            CommitTextCommand("foo.b", -1)
        )
        assertEquals("foo.b", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // Gboard marks gesture-typed words as recorrectable: the first backspace is eaten as
    // a lone empty commit, so it must delete the whole word (the terminal never saw a DEL).
    @Test
    fun backspaceAfterGestureTypingDeletesWholeWord() {
        val terminal = run(
            SetComposingTextCommand("gestured", 1),
            CommitTextCommand("", -1)
        )
        assertEquals("", terminal.netText())
        assertEquals(8, terminal.deleteCount())
    }

    // Spacebar swipe during an active composition: cursor moves back over the word, and
    // the same-text re-send is a no-op — nothing reaches the terminal twice.
    @Test
    fun spacebarSwipeDuringCompositionIsNoopForSameText() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            SetSelectionCommand(0, 0),
            SetComposingTextCommand("foo", 1)
        )
        assertEquals("foo", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // Spacebar swipe after the word is committed: selection syncs forward, then the swipe
    // moves the cursor back over the committed word.
    @Test
    fun spacebarSwipeAfterCommitMovesCursorBackOverWord() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo", -1),
            SetSelectionCommand(3, 3),
            SetSelectionCommand(0, 0)
        )
        val moves = terminal.events.filter {
            it.op == RecordingTerminalInput.Op.MOVE_LEFT || it.op == RecordingTerminalInput.Op.MOVE_RIGHT
        }
        assertEquals(
            listOf(
                RecordingTerminalInput.Event(RecordingTerminalInput.Op.MOVE_RIGHT, 3),
                RecordingTerminalInput.Event(RecordingTerminalInput.Op.MOVE_LEFT, 3)
            ),
            moves
        )
    }

    // Auto-correct replaces the composing word: delete it, send the replacement whole.
    @Test
    fun autoCorrectReplacementDeletesOldWordAndSendsNew() {
        val terminal = run(
            SetComposingTextCommand("teh", 1),
            CommitTextCommand("the", -1)
        )
        assertEquals("the", terminal.netText())
        assertEquals(3, terminal.deleteCount())
    }

    // Real backspace during composition removes exactly one code point.
    @Test
    fun backspaceDuringCompositionDeletesSingleCodePoint() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            SetComposingTextCommand("fo", 1)
        )
        assertEquals("fo", terminal.netText())
        assertEquals(1, terminal.deleteCount())
    }

    // A multi-character collapse is the IME cancelling the composition, not backspaces —
    // the text was already sent to the terminal as it grew.
    @Test
    fun multiCharCompositionCollapseIsCancelNotBackspaces() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            SetComposingTextCommand("", 1)
        )
        assertEquals("foo", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // A new word typed after a committed one is sent whole — never diffed against the
    // previously committed word unless it actually starts with it.
    @Test
    fun newWordAfterCommittedWordIsSentWhole() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo", -1),
            SetComposingTextCommand("bar", 1)
        )
        assertEquals("foobar", terminal.netText())
    }

    // Enter from the IME reaches the terminal as carriage return (VT semantics).
    @Test
    fun enterMapsToCarriageReturn() {
        val terminal = run(CommitTextCommand("ls\n", -1))
        assertEquals(listOf('l', 's', 13.toChar()), terminal.events.map { it.amount.toChar() })
    }
}

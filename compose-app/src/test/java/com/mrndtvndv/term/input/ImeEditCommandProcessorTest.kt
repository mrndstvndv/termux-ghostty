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

        fun moveCount(): Int = events.count { it.op == Op.MOVE_LEFT || it.op == Op.MOVE_RIGHT }
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

    // Third signature of the reported bug: the IME commits the word and the period as
    // separate commits. The period continues the tracked word ("foo" → "foo."), so the
    // cross-punctuation re-composition still diffs to just "b".
    @Test
    fun periodCommittedSeparatelyStillRecomposesOnlyTheSuffix() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo", -1),
            CommitTextCommand(".", -1),
            SetComposingTextCommand("foo.b", 1)
        )
        assertEquals("foo.b", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // Fourth signature: after the period commit the IME syncs the selection to the end of
    // the committed text. The sync must not move the terminal cursor nor clear the tracked
    // word, or the re-composition would re-send the whole region ("foo." + "foo.b").
    @Test
    fun selectionSyncAfterPeriodCommitDoesNotBreakRecomposition() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo.", -1),
            SetSelectionCommand(4, 4),
            SetComposingTextCommand("foo.b", 1)
        )
        assertEquals("foo.b", terminal.netText())
        assertEquals(0, terminal.deleteCount())
        assertEquals(0, terminal.moveCount())
    }

    // Full reproduction: word commit, selection sync, separate period commit, selection
    // sync, then the next character re-composes the region.
    @Test
    fun separatePeriodCommitWithSelectionSyncsRecomposesOnlyTheSuffix() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo", -1),
            SetSelectionCommand(3, 3),
            CommitTextCommand(".", -1),
            SetSelectionCommand(4, 4),
            SetComposingTextCommand("foo.b", 1)
        )
        assertEquals("foo.b", terminal.netText())
        assertEquals(0, terminal.deleteCount())
        assertEquals(0, terminal.moveCount())
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

    // Spacebar swipe after the word is committed: the selection sync to the end of the
    // committed text is a no-op (the terminal cursor is already there), then the swipe
    // moves the cursor back over the word.
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
            listOf(RecordingTerminalInput.Event(RecordingTerminalInput.Op.MOVE_LEFT, 3)),
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

    // Enter right after a committed word, pressed twice: the first Enter commit must not
    // become the tracked word, or the second Enter would be swallowed as a "re-commit"
    // of "\n" with an empty delta.
    @Test
    fun enterAfterCommittedWordWorksEveryTime() {
        val terminal = run(
            SetComposingTextCommand("foo", 1),
            CommitTextCommand("foo.", -1),
            CommitTextCommand("\n", -1),
            CommitTextCommand("\n", -1)
        )
        assertEquals("foo.\r\r", terminal.netText())
        assertEquals(0, terminal.deleteCount())
    }

    // Consecutive Enters with nothing else typed are all delivered.
    @Test
    fun consecutiveEntersAllReachTerminal() {
        val terminal = run(
            CommitTextCommand("\n", -1),
            CommitTextCommand("\n", -1),
            CommitTextCommand("\n", -1)
        )
        assertEquals("\r\r\r", terminal.netText())
    }
}

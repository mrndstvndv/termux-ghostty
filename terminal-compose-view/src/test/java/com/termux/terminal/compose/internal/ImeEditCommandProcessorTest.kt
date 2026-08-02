package com.termux.terminal.compose.internal

import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the IME edit-command state machine: composition growth must send only
 * deltas, commits must not re-send already-echoed text, auto-correct replaces
 * the composing region, and backspace/selection syncs must not corrupt the
 * tracked word used for cross-punctuation re-composition.
 */
class ImeEditCommandProcessorTest {

    private val terminal = RecordingTerminal()
    private val processor = ImeEditCommandProcessor(terminal)

    @Test
    fun directCommitSendsEveryCodePoint() {
        processor.process(listOf(CommitTextCommand("hi", 2)))

        assertEquals(listOf("cp:104", "cp:105"), terminal.events)
    }

    @Test
    fun composingGrowthSendsOnlyNewCharacters() {
        processor.process(listOf(SetComposingTextCommand("h", 1)))
        processor.process(listOf(SetComposingTextCommand("he", 2)))
        processor.process(listOf(SetComposingTextCommand("hel", 3)))

        assertEquals(listOf("cp:104", "cp:101", "cp:108"), terminal.events)
    }

    @Test
    fun commitAfterCompositionSendsOnlyTheSuffix() {
        processor.process(listOf(SetComposingTextCommand("hel", 3)))
        processor.process(listOf(CommitTextCommand("hello ", 6)))

        assertEquals(listOf("cp:104", "cp:101", "cp:108", "cp:108", "cp:111", "cp:32"), terminal.events)
    }

    @Test
    fun autocorrectReplacesTheComposingRegion() {
        processor.process(listOf(SetComposingTextCommand("teh", 3)))
        processor.process(listOf(CommitTextCommand("the", 3)))

        val expected = listOf(
            "cp:116", "cp:101", "cp:104",
            "del", "del", "del",
            "cp:116", "cp:104", "cp:101"
        )
        assertEquals(expected, terminal.events)
    }

    @Test
    fun standalonePunctuationContinuesTheTrackedWord() {
        commit("foo")
        processor.process(listOf(CommitTextCommand("foo.", 4)))

        assertEquals(listOf("cp:46"), terminal.events)
    }

    @Test
    fun recompositionAcrossPunctuationSendsOnlyTheNewCharacter() {
        commit("foo")
        processor.process(listOf(CommitTextCommand("foo.", 4)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 5)))

        assertEquals(listOf("cp:46", "cp:98"), terminal.events)
    }

    @Test
    fun postCommitSelectionSyncAtWordEndDoesNotMoveTheCursor() {
        commit("foo")
        processor.process(listOf(SetSelectionCommand(3, 3)))

        assertEquals(emptyList<String>(), terminal.events)
    }

    @Test
    fun selectionSyncAwayFromTheWordEndMovesTheCursorAndDropsTheTrackedWord() {
        commit("foo")
        processor.process(listOf(SetSelectionCommand(0, 0)))

        assertEquals(listOf("move:-3"), terminal.events)
    }

    @Test
    fun backspaceAfterCommitDeletesOneCharacter() {
        commit("abc")
        processor.process(listOf(BackspaceCommand()))

        assertEquals(listOf("del"), terminal.events)
    }

    @Test
    fun deleteSurroundingTextBeforeCursorDeletesPerCharacter() {
        commit("abc")
        processor.process(listOf(DeleteSurroundingTextCommand(2, 0)))

        assertEquals(listOf("del", "del"), terminal.events)
    }

    @Test
    fun emptyCommitDismissesTheGestureTypedWord() {
        processor.process(listOf(SetComposingTextCommand("hello", 5)))
        processor.process(listOf(CommitTextCommand("", 0)))

        val expected = listOf("cp:104", "cp:101", "cp:108", "cp:108", "cp:111") +
            List(5) { "del" }
        assertEquals(expected, terminal.events)
    }

    @Test
    fun compositionCancellationSendsNothing() {
        processor.process(listOf(SetComposingTextCommand("hello", 5)))
        processor.process(listOf(SetComposingTextCommand("", 0)))

        assertEquals(listOf("cp:104", "cp:101", "cp:108", "cp:108", "cp:111"), terminal.events)
    }

    @Test
    fun moveCursorCommandMovesByDelta() {
        commit("abc")
        processor.process(listOf(MoveCursorCommand(-2)))

        assertEquals(listOf("move:-2"), terminal.events)
    }

    @Test
    fun newWordAfterCommittedWordIsSentWholeAndStopsTracking() {
        commit("foo")
        processor.process(listOf(SetComposingTextCommand("bar", 3)))

        assertEquals(listOf("cp:98", "cp:97", "cp:114"), terminal.events)
    }

    @Test
    fun newlineCommitsAsCarriageReturn() {
        processor.process(listOf(CommitTextCommand("a\nb", 3)))

        assertEquals(listOf("cp:97", "cp:13", "cp:98"), terminal.events)
    }

    @Test
    fun deleteAllSendsTheBoundedDeleteBurst() {
        commit("abc")
        processor.process(listOf(DeleteAllCommand()))

        assertEquals(List(100) { "del" }, terminal.events)
    }

    @Test
    fun composingRegionCommandIsIgnored() {
        processor.process(listOf(SetComposingRegionCommand(0, 2)))

        assertEquals(emptyList<String>(), terminal.events)
    }

    private fun commit(text: String) {
        processor.process(listOf(SetComposingTextCommand(text, text.length)))
        processor.process(listOf(CommitTextCommand(text, text.length)))
        terminal.events.clear()
    }
}

private class RecordingTerminal : ImeEditCommandProcessor.TerminalInput {
    val events = mutableListOf<String>()

    override fun inputCodePoint(codePoint: Int) {
        events += "cp:$codePoint"
    }

    override fun delete() {
        events += "del"
    }

    override fun moveCursor(delta: Int) {
        events += "move:$delta"
    }
}

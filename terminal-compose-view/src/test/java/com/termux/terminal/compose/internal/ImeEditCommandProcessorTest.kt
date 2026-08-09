package com.termux.terminal.compose.internal

import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
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
        processor.process(listOf(CommitTextCommand("hi", 1)))

        assertEquals(listOf("cp:104", "cp:105"), terminal.events)
    }

    @Test
    fun composingGrowthSendsOnlyNewCharacters() {
        processor.process(listOf(SetComposingTextCommand("h", 1)))
        processor.process(listOf(SetComposingTextCommand("he", 1)))
        processor.process(listOf(SetComposingTextCommand("hel", 1)))

        assertEquals(listOf("cp:104", "cp:101", "cp:108"), terminal.events)
    }

    @Test
    fun commitAfterCompositionSendsOnlyTheSuffix() {
        processor.process(listOf(SetComposingTextCommand("hel", 1)))
        processor.process(listOf(CommitTextCommand("hello ", 1)))

        assertEquals(listOf("cp:104", "cp:101", "cp:108", "cp:108", "cp:111", "cp:32"), terminal.events)
    }

    @Test
    fun autocorrectReplacesTheComposingRegion() {
        processor.process(listOf(SetComposingTextCommand("teh", 1)))
        processor.process(listOf(CommitTextCommand("the", 1)))

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
        processor.process(listOf(CommitTextCommand("foo.", 1)))

        assertEquals(listOf("cp:46"), terminal.events)
    }

    @Test
    fun recompositionAcrossPunctuationSendsOnlyTheNewCharacter() {
        commit("foo")
        processor.process(listOf(CommitTextCommand("foo.", 1)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 1)))

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
        processor.process(listOf(SetComposingTextCommand("hello", 1)))
        processor.process(listOf(CommitTextCommand("", 0)))

        val expected = listOf("cp:104", "cp:101", "cp:108", "cp:108", "cp:111") +
            List(5) { "del" }
        assertEquals(expected, terminal.events)
    }

    @Test
    fun compositionCancellationSendsNothing() {
        processor.process(listOf(SetComposingTextCommand("hello", 1)))
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
        processor.process(listOf(SetComposingTextCommand("bar", 1)))

        assertEquals(listOf("cp:98", "cp:97", "cp:114"), terminal.events)
    }

    @Test
    fun newlineCommitsAsCarriageReturn() {
        processor.process(listOf(CommitTextCommand("a\nb", 1)))

        assertEquals(listOf("cp:97", "cp:13", "cp:98"), terminal.events)
    }

    @Test
    fun deleteAllSendsTheBoundedDeleteBurst() {
        commit("abc")
        processor.process(listOf(DeleteAllCommand()))

        assertEquals(List(100) { "del" }, terminal.events)
    }

    @Test
    fun composingRegionCommandDoesNotEmitTerminalInput() {
        processor.process(listOf(SetComposingRegionCommand(0, 2)))

        assertEquals(emptyList<String>(), terminal.events)
    }

    // Gboard re-sends the whole region ("foo.b") as a commit after the period commit;
    // only the suffix must reach the terminal.
    @Test
    fun commitAfterFinishComposingAcrossPunctuationRecomposesOnlyTheSuffix() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(FinishComposingTextCommand()))
        processor.process(listOf(CommitTextCommand("foo.", 1)))
        processor.process(listOf(CommitTextCommand("foo.b", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46", "cp:98"), terminal.events)
    }

    // The IME commits the word and the period as separate commits; the period continues
    // the tracked word so the cross-punctuation re-composition still diffs to "b".
    @Test
    fun separatePeriodCommitStillRecomposesOnlyTheSuffix() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand(".", 1)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46", "cp:98"), terminal.events)
    }

    // After the period commit the IME syncs the selection to the end of the committed
    // text; the sync must not move the terminal cursor nor clear the tracked word.
    @Test
    fun selectionSyncAfterPeriodCommitDoesNotBreakRecomposition() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand("foo.", 1)))
        processor.process(listOf(SetSelectionCommand(4, 4)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46", "cp:98"), terminal.events)
    }

    // Full Gboard reproduction: word commit, selection sync, separate period commit,
    // selection sync, then the next character re-composes the region.
    @Test
    fun separatePeriodCommitWithSelectionSyncsRecomposesOnlyTheSuffix() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand("foo", 1)))
        processor.process(listOf(SetSelectionCommand(3, 3)))
        processor.process(listOf(CommitTextCommand(".", 1)))
        processor.process(listOf(SetSelectionCommand(4, 4)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46", "cp:98"), terminal.events)
    }

    // Spacebar swipe during composition re-sends the same text; nothing reaches the
    // terminal twice.
    @Test
    fun spacebarSwipeDuringCompositionIsNoopForSameText() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(SetSelectionCommand(0, 0)))
        processor.process(listOf(SetComposingTextCommand("foo", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "move:-3"), terminal.events)
    }

    // Spacebar swipe after commit: the sync to the word end is a no-op, then the swipe
    // moves the cursor back over the word.
    @Test
    fun spacebarSwipeAfterCommitMovesCursorBackOverWord() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand("foo", 1)))
        processor.process(listOf(SetSelectionCommand(3, 3)))
        processor.process(listOf(SetSelectionCommand(0, 0)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "move:-3"), terminal.events)
    }

    // A real backspace during composition removes exactly one code point.
    @Test
    fun backspaceDuringCompositionDeletesSingleCodePoint() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(SetComposingTextCommand("fo", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "del"), terminal.events)
    }

    // The first Enter after a committed word must not become the tracked word, or the
    // second Enter would be swallowed as a re-commit of "\n" with an empty delta.
    @Test
    fun enterAfterCommittedWordWorksEveryTime() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand("foo.", 1)))
        processor.process(listOf(CommitTextCommand("\n", 1)))
        processor.process(listOf(CommitTextCommand("\n", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46", "cp:13", "cp:13"), terminal.events)
    }

    // Consecutive Enters with nothing else typed are all delivered.
    @Test
    fun consecutiveEntersAllReachTerminal() {
        processor.process(listOf(CommitTextCommand("\n", 1)))
        processor.process(listOf(CommitTextCommand("\n", 1)))
        processor.process(listOf(CommitTextCommand("\n", 1)))

        assertEquals(listOf("cp:13", "cp:13", "cp:13"), terminal.events)
    }

    @Test
    fun punctuationCommitWhileComposingKeepsTheWordForRecomposition() {
        processor.process(listOf(SetComposingTextCommand("foo", 1)))
        processor.process(listOf(CommitTextCommand(".", 1)))
        processor.process(listOf(SetComposingTextCommand("foo.b", 1)))

        assertEquals(
            listOf(
                "cp:102", "cp:111", "cp:111",
                "cp:46", "cp:98"
            ),
            terminal.events
        )
    }

    @Test
    fun selectionMovementUsesTerminalCodePointsForSurrogatePairs() {
        processor.process(listOf(SetComposingTextCommand("a😀", 1)))
        processor.process(listOf(SetSelectionCommand(1, 1)))

        assertEquals(listOf("cp:97", "cp:128512", "move:-1"), terminal.events)
    }

    @Test
    fun composingTextHonorsCursorPositionBeforeInsertedText() {
        processor.process(listOf(SetComposingTextCommand("foo", 0)))

        assertEquals(
            listOf("cp:102", "cp:111", "cp:111", "move:-3"),
            terminal.events
        )
    }

    @Test
    fun backspaceAtStartOfKnownCompositionDoesNotCrash() {
        processor.process(listOf(SetComposingTextCommand("a", 0)))
        terminal.events.clear()

        processor.process(listOf(BackspaceCommand()))

        assertEquals(listOf("del"), terminal.events)
    }

    @Test
    fun backspaceAfterSurrogatePairKeepsTheImeCursorAtTheTerminalCursor() {
        processor.process(listOf(SetComposingTextCommand("😀", 1)))
        processor.process(listOf(BackspaceCommand()))
        processor.process(listOf(SetSelectionCommand(0, 0)))

        assertEquals(listOf("cp:128512", "del"), terminal.events)
    }

    @Test
    fun deleteSurroundingTextAfterCursorUsesForwardDelete() {
        processor.process(listOf(SetComposingTextCommand("abc", 1)))
        processor.process(listOf(SetSelectionCommand(1, 1)))
        terminal.events.clear()
        processor.process(listOf(DeleteSurroundingTextCommand(0, 1)))

        assertEquals(listOf("move:1", "del", "move:-1"), terminal.events)
    }

    @Test
    fun resetDropsCrossPunctuationState() {
        commit("foo")
        processor.reset()
        processor.process(listOf(CommitTextCommand("foo.", 1)))

        assertEquals(listOf("cp:102", "cp:111", "cp:111", "cp:46"), terminal.events)
    }

    private fun commit(text: String) {
        processor.process(listOf(SetComposingTextCommand(text, 1)))
        processor.process(listOf(CommitTextCommand(text, 1)))
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

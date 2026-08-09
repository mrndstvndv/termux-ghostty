package com.termux.terminal.compose.internal

import androidx.compose.ui.text.input.BackspaceCommand
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteAllCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextInCodePointsCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.FinishComposingTextCommand
import androidx.compose.ui.text.input.MoveCursorCommand
import androidx.compose.ui.text.input.SetComposingRegionCommand
import androidx.compose.ui.text.input.SetComposingTextCommand
import androidx.compose.ui.text.input.SetSelectionCommand

/**
 * IME edit-command state machine for a terminal emulator.
 *
 * The soft keyboard drives an invisible text buffer the user never sees; this module
 * translates the IME's edit commands into semantic terminal operations. Because the
 * terminal already echoes text as it is typed, most commands must send only the *delta*
 * between the IME's buffer and what the terminal already contains:
 *
 * - Composition grows keystroke by keystroke: [SetComposingTextCommand] carries the whole
 *   word, so only the newly appended part reaches the terminal.
 * - Gboard keeps composing across punctuation: after "foo." is committed, typing "b"
 *   re-sends the entire region as "foo.b". The committed text is tracked in
 *   [lastComposedText] so the re-send collapses to just "b". The tracked word survives
 *   two commands that would otherwise break the diff and re-send the whole word:
 *   - the post-commit cursor sync, a [SetSelectionCommand] whose cursor is already at
 *     the end of the committed text (no terminal move, tracked word kept);
 *   - a standalone punctuation commit ("foo" then "."), which continues the tracked
 *     word instead of replacing it ("foo."), so the re-composition still diffs.
 * - Gesture-typed words are "recorrectable" in Gboard: the first backspace dismisses the
 *   correction with a lone empty [CommitTextCommand], which must delete the whole word
 *   (the terminal never saw a delete). The spacebar swipe instead sends
 *   [SetSelectionCommand] to move the cursor back over the word; same-text re-sends are
 *   no-ops.
 * - IME positions are UTF-16 offsets while terminal movement is in Unicode code points;
 *   cursor deltas are converted before they reach the backend.
 *
 * The module is backend-agnostic: it knows nothing about escape sequences or terminal
 * state, only that input reaches a [TerminalInput]. This keeps the state machine unit
 * testable and reusable if the terminal backend is replaced.
 */
@Suppress("TooManyFunctions") // one transition handler per IME command and cursor invariant
internal class ImeEditCommandProcessor(
    private val terminal: TerminalInput
) {

    /** Semantic operations the IME needs from a terminal backend. */
    interface TerminalInput {
        fun inputCodePoint(codePoint: Int)

        fun inputText(text: String) {
            var index = 0
            while (index < text.length) {
                val codePoint = text.codePointAt(index)
                inputCodePoint(if (codePoint == '\n'.code) 13 else codePoint)
                index += Character.charCount(codePoint)
            }
        }

        fun delete()

        /**
         * Deletes the code point immediately after the terminal cursor.
         *
         * Backends that do not have a native forward-delete operation use the
         * cursor movement fallback. The concrete terminal adapter overrides
         * this with its native key code.
         */
        fun deleteForward() {
            moveCursor(1)
            delete()
            moveCursor(-1)
        }

        /** Negative delta moves left, positive delta moves right. */
        fun moveCursor(delta: Int)
    }

    // The IME's active composing region (word currently being corrected).
    private var composingText = ""
    private var composingStartPosition = NO_POSITION

    // The last word committed to the terminal — Gboard may re-compose across it
    // (e.g. across a period), so it must be diffed against, not re-sent.
    private var lastComposedText = ""
    private var lastComposedStartPosition = NO_POSITION

    // Some IMEs announce the composing range before replacing it.
    private var pendingComposingStart = NO_POSITION

    // IME positions are UTF-16 offsets. Terminal movement is in Unicode code
    // points, so every cursor delta is converted before reaching the backend.
    private var imeCursorPosition = 0
    /** Drops IME-only state when the platform input session is recreated. */
    internal fun reset() {
        composingText = ""
        composingStartPosition = NO_POSITION
        clearTrackedCommit()
        pendingComposingStart = NO_POSITION
        imeCursorPosition = 0
    }

    fun process(commands: List<EditCommand>) {
        for (cmd in commands) {
            if (cmd !is SetComposingRegionCommand && cmd !is SetComposingTextCommand) {
                pendingComposingStart = NO_POSITION
            }
            when (cmd) {
                is CommitTextCommand -> handleCommitText(cmd.text, cmd.newCursorPosition)
                is DeleteSurroundingTextCommand -> handleDeleteBefore(
                    length = cmd.lengthBeforeCursor,
                    lengthIsCodePoints = false,
                    afterLength = cmd.lengthAfterCursor
                )
                is DeleteSurroundingTextInCodePointsCommand -> handleDeleteBefore(
                    length = cmd.lengthBeforeCursor,
                    lengthIsCodePoints = true,
                    afterLength = cmd.lengthAfterCursor
                )
                is BackspaceCommand -> {
                    val nextCursor = moveImeCursorByCodePoints(-1)
                    clearTrackedCommit()
                    composingText = ""
                    composingStartPosition = NO_POSITION
                    imeCursorPosition = nextCursor
                    terminal.delete()
                }
                is DeleteAllCommand -> {
                    clearTrackedCommit()
                    composingText = ""
                    composingStartPosition = NO_POSITION
                    imeCursorPosition = 0
                    repeat(DELETE_ALL_LIMIT) { terminal.delete() }
                }
                is MoveCursorCommand -> {
                    val nextCursor = moveImeCursorByCodePoints(cmd.amount)
                    clearTrackedCommit()
                    imeCursorPosition = nextCursor
                    terminal.moveCursor(cmd.amount)
                }
                is SetSelectionCommand -> handleSetSelection(cmd.start, cmd.end)
                is SetComposingTextCommand ->
                    handleSetComposingText(cmd.text, cmd.newCursorPosition)
                is SetComposingRegionCommand -> handleSetComposingRegion(cmd.start, cmd.end)
                is FinishComposingTextCommand -> handleFinishComposing()
            }
        }
    }

    private fun handleDeleteBefore(length: Int, lengthIsCodePoints: Boolean, afterLength: Int) {
        if (length <= 0 && afterLength <= 0) return

        val beforeCodePoints = if (lengthIsCodePoints) length else codePointCountBeforeCursor(length)
        val afterCodePoints = if (lengthIsCodePoints) afterLength else codePointCountAfterCursor(afterLength)
        val nextCursor = moveImeCursorByCodePoints(-beforeCodePoints)
        clearTrackedCommit()
        composingText = ""
        composingStartPosition = NO_POSITION

        repeat(beforeCodePoints) { terminal.delete() }
        imeCursorPosition = nextCursor

        repeat(afterCodePoints) { terminal.deleteForward() }
    }

    private fun handleCommitText(text: String, newCursorPosition: Int) {
        val activeComposition = composingText
        val trackedCommit = lastComposedText
        val regionStart = when {
            activeComposition.isNotEmpty() -> composingStartPosition
            trackedCommit.isNotEmpty() &&
                (text.startsWith(trackedCommit) || text.isPunctuationOnly()) ->
                lastComposedStartPosition
            else -> imeCursorPosition
        }.coerceAtLeast(0)

        when {
            activeComposition.isNotEmpty() -> handleActiveCompositionCommit(
                activeComposition,
                text,
                newCursorPosition,
                regionStart
            )
            trackedCommit.isNotEmpty() -> handleTrackedCommit(
                trackedCommit,
                text,
                newCursorPosition,
                regionStart
            )
            text.isNotEmpty() -> {
                sendCodePoints(text)
                placeCursorAfterInsertion(imeCursorPosition, text, newCursorPosition)
            }
            else -> clearTrackedCommit()
        }
    }

    private fun handleActiveCompositionCommit(
        activeComposition: String,
        text: String,
        newCursorPosition: Int,
        regionStart: Int
    ) {
        when {
            text.startsWith(activeComposition) -> {
                moveTerminalCursorTo(
                    from = imeCursorPosition,
                    to = regionStart + activeComposition.length,
                    regionStart = regionStart,
                    regionText = activeComposition
                )
                sendCodePoints(text.substring(activeComposition.length))
                finishCommit(regionStart, text, newCursorPosition)
            }
            text.isNotEmpty() && text.isPunctuationOnly() -> {
                // Gboard can commit a punctuation key independently while the
                // preceding word is still composing. Keep the joined region
                // available for re-composition.
                moveTerminalCursorTo(
                    from = imeCursorPosition,
                    to = regionStart + activeComposition.length,
                    regionStart = regionStart,
                    regionText = activeComposition
                )
                sendCodePoints(text)
                composingText = activeComposition + text
                composingStartPosition = regionStart
                clearTrackedCommit()
                placeCursorAfterInsertion(regionStart, composingText, newCursorPosition)
            }
            text.isEmpty() -> {
                moveTerminalCursorTo(
                    from = imeCursorPosition,
                    to = regionStart + activeComposition.length,
                    regionStart = regionStart,
                    regionText = activeComposition
                )
                repeat(activeComposition.codePointCount()) { terminal.delete() }
                clearComposition()
                clearTrackedCommit()
                imeCursorPosition = regionStart
            }
            else -> {
                replaceComposition(activeComposition, text, regionStart)
                finishCommit(regionStart, text, newCursorPosition)
            }
        }
    }

    private fun handleTrackedCommit(
        trackedCommit: String,
        text: String,
        newCursorPosition: Int,
        regionStart: Int
    ) {
        when {
            text.startsWith(trackedCommit) -> {
                moveTerminalCursorTo(
                    from = imeCursorPosition,
                    to = regionStart + trackedCommit.length,
                    regionStart = regionStart,
                    regionText = trackedCommit
                )
                sendCodePoints(text.substring(trackedCommit.length))
                lastComposedText = if (text.isWordLike()) text else ""
                lastComposedStartPosition =
                    if (lastComposedText.isNotEmpty()) regionStart else NO_POSITION
                placeCursorAfterInsertion(regionStart, text, newCursorPosition)
            }
            text.isNotEmpty() && text.isPunctuationOnly() -> {
                moveTerminalCursorTo(
                    from = imeCursorPosition,
                    to = regionStart + trackedCommit.length,
                    regionStart = regionStart,
                    regionText = trackedCommit
                )
                sendCodePoints(text)
                lastComposedText += text
                placeCursorAfterInsertion(regionStart, lastComposedText, newCursorPosition)
            }
            text.isNotEmpty() -> {
                sendCodePoints(text)
                clearTrackedCommit()
                placeCursorAfterInsertion(imeCursorPosition, text, newCursorPosition)
            }
            else -> clearTrackedCommit()
        }
    }

    private fun finishCommit(regionStart: Int, committedText: String, newCursorPosition: Int) {
        clearComposition()
        lastComposedText = if (committedText.isWordLike()) committedText else ""
        lastComposedStartPosition =
            if (lastComposedText.isNotEmpty()) regionStart else NO_POSITION
        placeCursorAfterInsertion(regionStart, committedText, newCursorPosition)
    }

    private fun replaceComposition(oldText: String, newText: String, regionStart: Int) {
        moveTerminalCursorTo(
            from = imeCursorPosition,
            to = regionStart + oldText.length,
            regionStart = regionStart,
            regionText = oldText
        )
        repeat(oldText.codePointCount()) { terminal.delete() }
        sendCodePoints(newText)
    }

    private fun handleSetSelection(start: Int, end: Int) {
        val target = start.coerceAtLeast(0)
        val diff = cursorDelta(imeCursorPosition, target)
        if (diff != 0) {
            // A non-zero selection move invalidates the cross-punctuation
            // prefix; the terminal cursor must follow the IME cursor.
            clearTrackedCommit()
            terminal.moveCursor(diff)
        }
        // A zero-diff selection is the post-commit sync. It must preserve the
        // tracked prefix so Gboard can re-compose across punctuation.
        imeCursorPosition = target
        if (start != end) {
            // The terminal has no IME selection surface. Keep the insertion
            // point at the range start; a following commit replaces through the
            // normal composition path.
            clearTrackedCommit()
        }
    }

    private fun handleSetComposingRegion(start: Int, end: Int) {
        pendingComposingStart = minOf(start, end).coerceAtLeast(0)
    }

    private fun handleSetComposingText(current: String, newCursorPosition: Int) {
        val previousComposition = composingText
        val trackedCommit = lastComposedText
        val regionStart = when {
            previousComposition.isNotEmpty() -> composingStartPosition
            trackedCommit.isNotEmpty() && current.startsWith(trackedCommit) ->
                lastComposedStartPosition
            pendingComposingStart != NO_POSITION -> pendingComposingStart
            else -> imeCursorPosition
        }.coerceAtLeast(0)
        pendingComposingStart = NO_POSITION

        // Gboard uses an unchanged composing string while the spacebar gesture
        // moves the cursor. The SetSelection command already moved the
        // terminal; moving back to the text end here would cause a cursor warp.
        if (current == previousComposition) return

        if (previousComposition.isEmpty()) {
            handleFreshComposition(current, trackedCommit, regionStart)
        } else if (handleExistingCompositionChange(previousComposition, current, regionStart)) {
            return
        }

        clearTrackedCommit()
        composingText = current
        composingStartPosition = if (current.isNotEmpty()) regionStart else NO_POSITION
        if (current.isNotEmpty()) {
            placeCursorAfterInsertion(regionStart, current, newCursorPosition)
        } else {
            imeCursorPosition = regionStart
        }
    }

    private fun handleFreshComposition(current: String, trackedCommit: String, regionStart: Int) {
        if (trackedCommit.isNotEmpty() && current.startsWith(trackedCommit)) {
            moveTerminalCursorTo(
                from = imeCursorPosition,
                to = regionStart + trackedCommit.length,
                regionStart = regionStart,
                regionText = trackedCommit
            )
            sendCodePoints(current.substring(trackedCommit.length))
        } else {
            sendCodePoints(current)
        }
        clearTrackedCommit()
    }

    /** Returns true when the composition state was fully handled by the branch. */
    private fun handleExistingCompositionChange(
        previousComposition: String,
        current: String,
        regionStart: Int
    ): Boolean = when {
        current.length > previousComposition.length &&
            current.startsWith(previousComposition) &&
            imeCursorPosition == regionStart + previousComposition.length -> {
            sendCodePoints(current.substring(previousComposition.length))
            false
        }
        current.isEmpty() -> {
            cancelComposition(previousComposition, regionStart)
            true
        }
        current.length < previousComposition.length &&
            previousComposition.startsWith(current) ->
            handleCompositionShrink(previousComposition, current, regionStart)
        else -> {
            replaceComposition(previousComposition, current, regionStart)
            false
        }
    }

    private fun handleCompositionShrink(
        previousComposition: String,
        current: String,
        regionStart: Int
    ): Boolean {
        val removedCodePoints =
            previousComposition.codePointCount() - current.codePointCount()
        return when {
            removedCodePoints == 1 && imeCursorPosition == regionStart + previousComposition.length -> {
                terminal.delete()
                false
            }
            removedCodePoints > 1 -> {
                cancelComposition(previousComposition, regionStart)
                true
            }
            else -> false
        }
    }

    private fun cancelComposition(previousComposition: String, regionStart: Int) {
        // Composition cancellation does not mean terminal deletion: the
        // terminal already received the composing text. Keep it as a tracked
        // commit and align the cursor with its terminal end.
        lastComposedText = previousComposition
        lastComposedStartPosition = regionStart
        clearComposition()
        imeCursorPosition = regionStart + previousComposition.length
    }

    private fun handleFinishComposing() {
        if (composingText.isNotEmpty()) {
            lastComposedText = if (composingText.isWordLike()) composingText else ""
            lastComposedStartPosition =
                if (lastComposedText.isNotEmpty()) composingStartPosition else NO_POSITION
            clearComposition()
        }
    }

    private fun clearComposition() {
        composingText = ""
        composingStartPosition = NO_POSITION
    }

    private fun clearTrackedCommit() {
        lastComposedText = ""
        lastComposedStartPosition = NO_POSITION
    }

    private fun placeCursorAfterInsertion(
        insertionStart: Int,
        insertedText: String,
        newCursorPosition: Int
    ) {
        val end = insertionStart + insertedText.length
        val target = if (newCursorPosition > 0) {
            end + newCursorPosition - 1
        } else {
            insertionStart + newCursorPosition
        }.coerceAtLeast(0)
        moveTerminalCursorTo(
            from = end,
            to = target,
            regionStart = insertionStart,
            regionText = insertedText
        )
        imeCursorPosition = target
    }

    private fun moveTerminalCursorTo(from: Int, to: Int, regionStart: Int, regionText: String) {
        val diff = cursorDelta(from, to, regionStart, regionText)
        if (diff != 0) terminal.moveCursor(diff)
    }

    private fun cursorDelta(from: Int, to: Int): Int {
        val region = when {
            composingText.isNotEmpty() -> composingStartPosition to composingText
            lastComposedText.isNotEmpty() -> lastComposedStartPosition to lastComposedText
            else -> null
        }
        return if (region == null) {
            to - from
        } else {
            cursorDelta(from, to, region.first, region.second)
        }
    }

    private fun cursorDelta(from: Int, to: Int, regionStart: Int, regionText: String): Int {
        val regionEnd = regionStart + regionText.length
        if (from !in regionStart..regionEnd || to !in regionStart..regionEnd) {
            return to - from
        }
        val fromLocal = from - regionStart
        val toLocal = to - regionStart
        return if (toLocal >= fromLocal) {
            regionText.codePointCount(fromLocal, toLocal)
        } else {
            -regionText.codePointCount(toLocal, fromLocal)
        }
    }

    private fun moveImeCursorByCodePoints(amount: Int): Int =
        if (amount == 0) imeCursorPosition else moveImeCursorWithinKnownRegion(amount)

    private fun moveImeCursorWithinKnownRegion(amount: Int): Int {
        val region = knownCursorRegion()
        if (region == null) return (imeCursorPosition + amount).coerceAtLeast(0)
        val (regionStart, regionText) = region
        val regionEnd = regionStart + regionText.length
        if (imeCursorPosition !in regionStart..regionEnd) {
            return (imeCursorPosition + amount).coerceAtLeast(0)
        }
        val localPosition = imeCursorPosition - regionStart
        val currentCodePoint = regionText.codePointCount(0, localPosition)
        val targetCodePoint = (currentCodePoint + amount)
            .coerceIn(0, regionText.codePointCount())
        return regionStart + regionText.offsetByCodePoints(0, targetCodePoint)
    }

    private fun knownCursorRegion(): Pair<Int, String>? = when {
        composingText.isNotEmpty() -> composingStartPosition to composingText
        lastComposedText.isNotEmpty() -> lastComposedStartPosition to lastComposedText
        else -> null
    }

    private fun codePointCountBeforeCursor(length: Int): Int {
        val region = knownCursorRegion() ?: return length
        val (regionStart, regionText) = region
        val end = imeCursorPosition
        val start = (end - length).coerceAtLeast(regionStart)
        val regionEnd = regionStart + regionText.length
        return if (start in regionStart..regionEnd && end in regionStart..regionEnd) {
            regionText.codePointCount(start - regionStart, end - regionStart)
        } else {
            length
        }
    }

    private fun codePointCountAfterCursor(length: Int): Int {
        val region = knownCursorRegion() ?: return length
        val (regionStart, regionText) = region
        val start = imeCursorPosition
        val end = (start + length).coerceAtMost(regionStart + regionText.length)
        val regionEnd = regionStart + regionText.length
        return if (start in regionStart..regionEnd && end in regionStart..regionEnd) {
            regionText.codePointCount(start - regionStart, end - regionStart)
        } else {
            length
        }
    }
    /** True when the text is a punctuation-only continuation ("." after "foo" → "foo."). */
    private fun String.isPunctuationOnly(): Boolean =
        isNotEmpty() && all { !it.isLetterOrDigit() && !it.isWhitespace() }

    /** True when the text contains a letter or digit that Gboard can re-compose across. */
    private fun String.isWordLike(): Boolean = any { it.isLetterOrDigit() }

    private fun String.codePointCount(): Int = Character.codePointCount(this, 0, length)

    private fun sendCodePoints(text: String) {
        if (text.isNotEmpty()) terminal.inputText(text)
    }

    private companion object {
        const val NO_POSITION = -1
        const val DELETE_ALL_LIMIT = 100
    }
}

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
 *
 * The module is backend-agnostic: it knows nothing about escape sequences or terminal
 * state, only that input reaches a [TerminalInput]. This keeps the state machine unit
 * testable and reusable if the terminal backend is replaced.
 */
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
        /** Negative delta moves left, positive moves right. */
        fun moveCursor(delta: Int)
    }

    // The IME's composing region (word currently being corrected by the keyboard).
    private var composingText = ""
    // The last word committed to the terminal — Gboard may re-compose across it
    // (e.g. across a period), so it must be diffed against, not re-sent.
    private var lastComposedText = ""
    // The IME cursor position in its own buffer. Kept in sync through composing,
    // commits, deletes and moves so a post-commit selection sync (cursor already at
    // the end of the committed text) resolves to zero moves instead of displacing
    // the terminal cursor.
    private var imeCursorPosition = 0

    fun process(commands: List<EditCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is CommitTextCommand -> handleCommitText(cmd.text)
                is DeleteSurroundingTextCommand -> {
                    if (cmd.lengthBeforeCursor > 0) {
                        lastComposedText = ""
                        imeCursorPosition = (imeCursorPosition - cmd.lengthBeforeCursor).coerceAtLeast(0)
                        repeat(cmd.lengthBeforeCursor) { terminal.delete() }
                    }
                }
                is DeleteSurroundingTextInCodePointsCommand -> {
                    if (cmd.lengthBeforeCursor > 0) {
                        lastComposedText = ""
                        imeCursorPosition = (imeCursorPosition - cmd.lengthBeforeCursor).coerceAtLeast(0)
                        repeat(cmd.lengthBeforeCursor) { terminal.delete() }
                    }
                }
                is BackspaceCommand -> {
                    lastComposedText = ""
                    imeCursorPosition = (imeCursorPosition - 1).coerceAtLeast(0)
                    terminal.delete()
                }
                is DeleteAllCommand -> {
                    lastComposedText = ""
                    imeCursorPosition = 0
                    repeat(100) { terminal.delete() }
                }
                is MoveCursorCommand -> {
                    lastComposedText = ""
                    imeCursorPosition = (imeCursorPosition + cmd.amount).coerceAtLeast(0)
                    terminal.moveCursor(cmd.amount)
                }
                is SetSelectionCommand -> handleSetSelection(cmd.start)
                is SetComposingTextCommand -> handleSetComposingText(cmd.text)
                is SetComposingRegionCommand -> { }
                is FinishComposingTextCommand -> handleFinishComposing()
            }
        }
    }

    private fun handleCommitText(text: String) {
        // Start of the IME-buffer region this commit replaces (the composing region,
        // the tracked word, or the cursor position). Used to keep imeCursorPosition
        // consistent across separate commits of one word ("foo" then ".").
        val regionStart = when {
            composingText.isNotEmpty() -> (imeCursorPosition - composingText.length).coerceAtLeast(0)
            lastComposedText.isNotEmpty() && text.startsWith(lastComposedText) ->
                (imeCursorPosition - lastComposedText.length).coerceAtLeast(0)
            else -> imeCursorPosition
        }
        when {
            // Active composition — the word was already echoed to the terminal as it grew.
            composingText.isNotEmpty() -> {
                when {
                    text.startsWith(composingText) -> {
                        // Normal finalization — send only the suffix (space, punctuation)
                        val delta = text.substring(composingText.length)
                        sendCodePoints(delta)
                        // Keep tracking the committed text: Gboard may re-compose
                        // across punctuation ("foo." + "b" → "foo.b") and re-send it.
                        lastComposedText = if (text.isWordLike()) text else ""
                    }
                    text.isEmpty() -> {
                        // Lone empty commit = Gboard dismissing the recorrection
                        // on the first backspace press after gesture typing (the
                        // swipe sends SetSelectionCommand instead, never this).
                        // The user expects one backspace to remove the gestured
                        // word — delete the whole composing word.
                        repeat(composingText.length) { terminal.delete() }
                        lastComposedText = ""
                    }
                    else -> {
                        // Auto-correct replaced the composing text entirely
                        repeat(composingText.length) { terminal.delete() }
                        sendCodePoints(text)
                        lastComposedText = if (text.isWordLike()) text else ""
                    }
                }
                composingText = ""
            }
            // Composition already finished — the word is in the terminal. Never delete;
            // send only the suffix if this commit continues the word, else direct input.
            lastComposedText.isNotEmpty() -> {
                when {
                    text.startsWith(lastComposedText) -> {
                        // Continuation (or re-commit) of the tracked word — only the suffix
                        // is new, the rest is already echoed in the terminal.
                        val delta = text.substring(lastComposedText.length)
                        sendCodePoints(delta)
                        lastComposedText = text
                    }
                    text.isNotEmpty() && text.isPunctuationOnly() -> {
                        // Gboard may commit the word and its punctuation separately
                        // ("foo" then "."). The punctuation continues the word in the
                        // terminal buffer ("foo."), and Gboard re-composes across it —
                        // keep the joined text as the diff prefix so the re-send
                        // collapses to just the next character.
                        sendCodePoints(text)
                        lastComposedText += text
                    }
                    text.isNotEmpty() -> {
                        // New word after the committed one — send it whole. Whitespace-only
                        // text (notably the Enter key's "\n") is never tracked as the
                        // committed word: the next Enter would then look like a re-commit
                        // of it and be swallowed with an empty delta.
                        sendCodePoints(text)
                        lastComposedText = if (text.isWordLike()) text else ""
                    }
                    else -> {
                        lastComposedText = ""
                    }
                }
            }
            // No composition — direct input
            else -> sendCodePoints(text)
        }
        composingText = ""
        imeCursorPosition = regionStart + text.length
    }

    private fun handleSetSelection(start: Int) {
        val diff = start - imeCursorPosition
        if (diff != 0) {
            // The cursor moved away from the tracked word — it can no longer be diffed
            // against, and the terminal cursor must follow.
            lastComposedText = ""
            terminal.moveCursor(diff)
        }
        // A zero diff is the post-commit selection sync: the IME cursor is already where
        // the terminal's is, so nothing moves and the tracked word survives for the
        // cross-punctuation re-composition that follows.
        imeCursorPosition = start
    }

    private fun handleSetComposingText(current: String) {
        val regionStart = when {
            composingText.isNotEmpty() -> (imeCursorPosition - composingText.length).coerceAtLeast(0)
            lastComposedText.isNotEmpty() && current.startsWith(lastComposedText) ->
                (imeCursorPosition - lastComposedText.length).coerceAtLeast(0)
            else -> imeCursorPosition
        }
        when {
            // Gboard keeps composing across punctuation: after "foo." was committed,
            // typing "b" re-sends the whole region as "foo.b". The old text is
            // already in the terminal — only the new suffix reaches it.
            composingText.isEmpty() && lastComposedText.isNotEmpty() &&
                current.startsWith(lastComposedText) -> {
                val delta = current.substring(lastComposedText.length)
                sendCodePoints(delta)
                lastComposedText = ""
            }
            // Fresh word composing after a committed one — send it whole.
            composingText.isEmpty() && lastComposedText.isNotEmpty() -> {
                sendCodePoints(current)
                lastComposedText = ""
            }
            // Same text with a different cursor position (Gboard spacebar-swipe cursor
            // mode, recorrection). Nothing changed in the terminal — ignore.
            current == composingText -> { }
            // Append: composing text grew (new characters typed)
            current.length > composingText.length && current.startsWith(composingText) -> {
                sendCodePoints(current.substring(composingText.length))
            }
            // Shrink: a real backspace removes one code point; a multi-character
            // collapse is the IME cancelling the composition (e.g. Gboard spacebar
            // swipe) and must not become backspaces — the text was already sent to
            // the terminal as it grew.
            composingText.length > current.length && composingText.startsWith(current) -> {
                val removedCodePoints = Character.codePointCount(composingText, 0, composingText.length) -
                    Character.codePointCount(current, 0, current.length)
                if (removedCodePoints == 1) {
                    terminal.delete()
                }
            }
            // Replacement: text was corrected/replaced entirely (auto-correct, suggestion)
            composingText.isNotEmpty() -> {
                repeat(composingText.length) { terminal.delete() }
                sendCodePoints(current)
            }
            // First composition character
            else -> sendCodePoints(current)
        }
        composingText = current
        imeCursorPosition = regionStart + current.length
    }

    private fun handleFinishComposing() {
        if (composingText.isNotEmpty()) {
            lastComposedText = if (composingText.isWordLike()) composingText else ""
            composingText = ""
        }
    }

    /** True when the text is a punctuation-only continuation ("." after "foo" → "foo."). */
    private fun String.isPunctuationOnly(): Boolean {
        if (isEmpty()) return false
        return all { !it.isLetterOrDigit() && !it.isWhitespace() }
    }

    /** True when the text contains a letter or digit — a real word that Gboard could
     * re-compose across, unlike "\n" or a lone space. */
    private fun String.isWordLike(): Boolean = any { it.isLetterOrDigit() }

    private fun sendCodePoints(text: String) {
        if (text.isNotEmpty()) terminal.inputText(text)
    }
}

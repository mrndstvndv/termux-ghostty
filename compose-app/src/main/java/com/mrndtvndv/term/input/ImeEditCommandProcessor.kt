package com.mrndtvndv.term.input

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
 *   [lastComposedText] so the re-send collapses to just "b".
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
class ImeEditCommandProcessor(
    private val terminal: TerminalInput
) {

    /** Semantic operations the IME needs from a terminal backend. */
    interface TerminalInput {
        fun inputCodePoint(codePoint: Int)
        fun delete()
        /** Negative delta moves left, positive moves right. */
        fun moveCursor(delta: Int)
    }

    // The IME's composing region (word currently being corrected by the keyboard).
    private var composingText = ""
    // The last word committed to the terminal — Gboard may re-compose across it
    // (e.g. across a period), so it must be diffed against, not re-sent.
    private var lastComposedText = ""
    // Track previous cursor position for Gboard swipe detection.
    private var previousCursorPosition = 0

    fun process(commands: List<EditCommand>) {
        for (cmd in commands) {
            when (cmd) {
                is CommitTextCommand -> handleCommitText(cmd.text)
                is DeleteSurroundingTextCommand -> {
                    lastComposedText = ""
                    repeat(cmd.lengthBeforeCursor) { terminal.delete() }
                }
                is DeleteSurroundingTextInCodePointsCommand -> {
                    lastComposedText = ""
                    repeat(cmd.lengthBeforeCursor) { terminal.delete() }
                }
                is BackspaceCommand -> {
                    lastComposedText = ""
                    terminal.delete()
                }
                is DeleteAllCommand -> {
                    lastComposedText = ""
                    repeat(100) { terminal.delete() }
                }
                is MoveCursorCommand -> {
                    lastComposedText = ""
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
                        lastComposedText = text
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
                        lastComposedText = text
                    }
                }
                composingText = ""
            }
            // Composition already finished — the word is in the terminal. Never delete;
            // send only the suffix if this commit continues the word, else direct input.
            lastComposedText.isNotEmpty() -> {
                if (text.startsWith(lastComposedText)) {
                    // Continuation (or re-commit) of the tracked word — only the suffix
                    // is new, the rest is already echoed in the terminal.
                    val delta = text.substring(lastComposedText.length)
                    sendCodePoints(delta)
                    lastComposedText = text
                } else if (text.isNotEmpty()) {
                    // New word after the committed one — send it whole.
                    sendCodePoints(text)
                    lastComposedText = text
                } else {
                    lastComposedText = ""
                }
            }
            // No composition — direct input
            else -> sendCodePoints(text)
        }
    }

    private fun handleSetSelection(start: Int) {
        lastComposedText = ""
        val diff = start - previousCursorPosition
        if (diff != 0) {
            terminal.moveCursor(diff)
        }
        previousCursorPosition = start
    }

    private fun handleSetComposingText(current: String) {
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
    }

    private fun handleFinishComposing() {
        if (composingText.isNotEmpty()) {
            lastComposedText = composingText
            composingText = ""
        }
    }

    private fun sendCodePoints(text: String) {
        for (char in text) {
            val cp = if (char == '\n') 13 else char.code
            terminal.inputCodePoint(cp)
        }
    }
}

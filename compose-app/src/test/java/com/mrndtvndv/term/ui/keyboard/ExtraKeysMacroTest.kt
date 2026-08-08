package com.mrndtvndv.term.ui.keyboard

import com.termux.terminal.KeyHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class ExtraKeysMacroTest {
    @Test
    fun encodesTmuxPreviousWindowMacroAsOnePayload() {
        assertEquals("\u0002p", encodeExtraKeyMacro("CTRL b p", false, false))
    }

    @Test
    fun preservesKittyCtrlShiftEncoding() {
        assertEquals("\u001b[74;6u", encodeExtraKeyMacro("CTRL SHIFT J", false, false))
    }

    @Test
    fun encodesEscapeImmediatelyWhenKittyDisambiguationIsEnabled() {
        assertEquals(
            "\u001b[27u",
            encodeExtraKeyMacro(
                "ESC",
                false,
                false,
                kittyKeyboardFlags = KeyHandler.KITTY_KEYBOARD_FLAG_DISAMBIGUATE_ESCAPE
            )
        )
    }

    @Test
    fun encodesAliasesUsingTerminalApplicationModes() {
        assertEquals("\u001bOA", encodeExtraKeyMacro("UP", true, false))
    }

    @Test
    fun resetsModifiersAfterEachMacroToken() {
        assertEquals("\u0002N", encodeExtraKeyMacro("CTRL b N", false, false))
    }
}

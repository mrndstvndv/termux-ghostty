package com.mrndtvndv.term.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class KeyboardToggleFabTest {
    @Test
    fun fabHiddenWhenDisabledRegardlessOfKeyboard() {
        assertEquals(false, isKeyboardFabVisible(false, false, false))
        assertEquals(false, isKeyboardFabVisible(false, true, true))
    }

    @Test
    fun fabAutoHidesWhileTypingOnlyWhenAllowed() {
        assertEquals(false, isKeyboardFabVisible(true, true, true))
        assertEquals(true, isKeyboardFabVisible(true, false, true))
        assertEquals(true, isKeyboardFabVisible(true, true, false))
    }
}

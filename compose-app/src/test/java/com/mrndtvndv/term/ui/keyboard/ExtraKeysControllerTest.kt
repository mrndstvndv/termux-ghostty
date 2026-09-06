package com.mrndtvndv.term.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeysControllerTest {
    @Test
    fun readingOneShotModifierDoesNotMutateComposeStateDuringDispatch() {
        val controller = ExtraKeysController()
        controller.toggleControl()

        assertTrue(controller.readControl())
        assertEquals(ModifierState.ACTIVE, controller.ctrlState)
        assertTrue(controller.readControl())

        controller.clearConsumedModifiers()

        assertEquals(ModifierState.INACTIVE, controller.ctrlState)
        assertEquals(false, controller.readControl())
    }

    @Test
    fun lockedModifierSurvivesOneShotCleanup() {
        val controller = ExtraKeysController()
        controller.lockControl()

        assertTrue(controller.readControl())
        controller.clearConsumedModifiers()

        assertEquals(ModifierState.LOCKED, controller.ctrlState)
        assertTrue(controller.readControl())
    }

    @Test
    fun keyboardToggleKeyMatchesLegacyToggleName() {
        assertTrue(isKeyboardToggleKey("KEYBOARD"))
        assertTrue(isKeyboardToggleKey("keyboard"))
        assertEquals(false, isKeyboardToggleKey("ESC"))
        assertEquals(false, isKeyboardToggleKey("PASTE"))
        assertEquals(false, isKeyboardToggleKey("CTRL"))
    }

}

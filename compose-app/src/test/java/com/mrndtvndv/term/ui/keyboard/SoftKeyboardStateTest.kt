package com.mrndtvndv.term.ui.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SoftKeyboardStateTest {
    @Test
    fun `unknown is used for missing and invalid preferences`() {
        assertEquals(SoftKeyboardState.UNKNOWN, SoftKeyboardState.fromPreference(null))
        assertEquals(SoftKeyboardState.UNKNOWN, SoftKeyboardState.fromPreference("other"))
    }

    @Test
    fun `tracker ignores initial hidden state`() {
        val tracker = SoftKeyboardVisibilityTracker()

        assertEquals(null, tracker.observe(false, isTerminalActive = true, isLifecycleResumed = true))
    }

    @Test
    fun `tracker reports visible and later hidden transitions`() {
        val tracker = SoftKeyboardVisibilityTracker()

        assertEquals(true, tracker.observe(true, isTerminalActive = true, isLifecycleResumed = true))
        assertEquals(null, tracker.observe(true, isTerminalActive = true, isLifecycleResumed = true))
        assertEquals(false, tracker.observe(false, isTerminalActive = true, isLifecycleResumed = true))
    }

    @Test
    fun `tracker does not persist lifecycle or inactive tab changes`() {
        val tracker = SoftKeyboardVisibilityTracker()

        assertEquals(null, tracker.observe(true, isTerminalActive = false, isLifecycleResumed = true))
        assertEquals(null, tracker.observe(false, isTerminalActive = true, isLifecycleResumed = false))
        assertEquals(null, tracker.observe(false, isTerminalActive = true, isLifecycleResumed = true))
    }
}

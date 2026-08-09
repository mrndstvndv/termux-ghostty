package com.mrndtvndv.term.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSessionCommandAdapterTest {
    @Test
    fun mouseTrackingRoutesScrollToTerminalInputInsteadOfScrollback() {
        assertTrue(
            scrollUsesTerminalInput(
                mouseTrackingActive = true,
                alternateBufferActive = false
            )
        )
    }

    @Test
    fun alternateBufferRoutesScrollToTerminalInputInsteadOfScrollback() {
        assertTrue(
            scrollUsesTerminalInput(
                mouseTrackingActive = false,
                alternateBufferActive = true
            )
        )
    }

    @Test
    fun ordinaryShellRoutesScrollToScrollback() {
        assertFalse(
            scrollUsesTerminalInput(
                mouseTrackingActive = false,
                alternateBufferActive = false
            )
        )
    }
}

package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCanvasConfigTest {
    @Test
    fun `default maximum font size accommodates zoom up to 256`() {
        val config = TerminalCanvasConfig()

        assertEquals(8, config.minimumFontSize)
        assertEquals(256, config.maximumFontSize)
        assertEquals(128, config.clampedFontSize(128))
    }
}

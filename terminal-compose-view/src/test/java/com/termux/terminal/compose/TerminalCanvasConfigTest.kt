package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCanvasConfigTest {
    @Test
    fun `default maximum font size accommodates zoom up to 256`() {
        val config = TerminalCanvasConfig()

        assertEquals(8, config.minimumFontSize)
        assertEquals(256, config.maximumFontSize)
        assertEquals(128, config.clampedFontSize(128))
    }

    @Test
    fun `keyboard policy defaults preserve tap auto-show and swipe-up gesture`() {
        val config = TerminalCanvasConfig()

        assertEquals(true, config.unconditionalKeyboardOnTap)
        assertEquals(true, config.autoShowKeyboardOnTap)
        assertEquals(true, config.twoFingerSwipeUpOpensKeyboard)
    }

    @Test
    fun `scrollbar config defaults and custom overrides`() {
        val defaultConfig = TerminalCanvasConfig()
        assertEquals(true, defaultConfig.scrollbar.enabled)
        assertTrue(defaultConfig.scrollbar.visibility is ScrollbarVisibility.AutoFade)

        val customScrollbar = TerminalScrollbarConfig(
            enabled = false,
            visibility = ScrollbarVisibility.Always
        )
        val customConfig = defaultConfig.copy(scrollbar = customScrollbar)
        assertEquals(false, customConfig.scrollbar.enabled)
        assertEquals(ScrollbarVisibility.Always, customConfig.scrollbar.visibility)
    }
}

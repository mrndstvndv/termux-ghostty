package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCanvasConfigTest {
    @Test
    fun `OpenGL ES renderer is the default`() {
        assertEquals(TerminalRenderer.OPENGL_ES, TerminalCanvasConfig().renderer)
    }

    @Test
    fun `plain OpenGL ES frames bypass Compose invalidation`() {
        assertFalse(
            requiresComposeFrameUpdate(
                renderer = TerminalRenderer.OPENGL_ES,
                accessibilityEnabled = false,
                selectionActive = false
            )
        )
    }

    @Test
    fun `OpenGL ES overlays retain Compose invalidation`() {
        assertTrue(
            requiresComposeFrameUpdate(
                renderer = TerminalRenderer.OPENGL_ES,
                accessibilityEnabled = true,
                selectionActive = false
            )
        )
        assertTrue(
            requiresComposeFrameUpdate(
                renderer = TerminalRenderer.OPENGL_ES,
                accessibilityEnabled = false,
                selectionActive = true
            )
        )
    }

    @Test
    fun `Compose renderer always retains Compose invalidation`() {
        assertTrue(
            requiresComposeFrameUpdate(
                renderer = TerminalRenderer.COMPOSE,
                accessibilityEnabled = false,
                selectionActive = false
            )
        )
    }

    @Test
    fun `Compose renderer remains an explicit fallback`() {
        val config = TerminalCanvasConfig(renderer = TerminalRenderer.COMPOSE)

        assertEquals(TerminalRenderer.COMPOSE, config.renderer)
    }

    @Test
    fun `default maximum font size accommodates zoom up to 256`() {
        val config = TerminalCanvasConfig()

        assertEquals(8, config.minimumFontSize)
        assertEquals(256, config.maximumFontSize)
        assertEquals(128, config.clampedFontSize(128))
    }
}

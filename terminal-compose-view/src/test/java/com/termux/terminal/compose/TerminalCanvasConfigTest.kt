package com.termux.terminal.compose

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalCanvasConfigTest {
    @Test
    fun `compose renderer remains the default`() {
        assertEquals(TerminalRenderer.COMPOSE, TerminalCanvasConfig().renderer)
    }

    @Test
    fun `OpenGL ES renderer is an explicit policy choice`() {
        val config = TerminalCanvasConfig(renderer = TerminalRenderer.OPENGL_ES)

        assertEquals(TerminalRenderer.OPENGL_ES, config.renderer)
    }
}

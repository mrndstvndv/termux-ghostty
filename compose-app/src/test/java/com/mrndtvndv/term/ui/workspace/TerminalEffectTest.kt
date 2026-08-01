package com.mrndtvndv.term.ui.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEffectTest {
    @Test
    fun scanlinesDoesNotDeclareOptionalUniforms() {
        assertFalse(TerminalEffect.SCANLINES.usesTimeUniform)
        assertFalse(TerminalEffect.SCANLINES.usesResolutionUniform)
    }

    @Test
    fun staticEffectsDeclareOnlyUniformsUsedByTheirSources() {
        assertFalse(TerminalEffect.CRT.usesTimeUniform)
        assertTrue(TerminalEffect.CRT.usesResolutionUniform)
        assertFalse(TerminalEffect.VIGNETTE.usesTimeUniform)
        assertTrue(TerminalEffect.VIGNETTE.usesResolutionUniform)
    }

    @Test
    fun animatedEffectsDeclareBothAnimationUniforms() {
        assertTrue(TerminalEffect.GLITCH.usesTimeUniform)
        assertTrue(TerminalEffect.GLITCH.usesResolutionUniform)
        assertTrue(TerminalEffect.MATRIX.usesTimeUniform)
        assertTrue(TerminalEffect.MATRIX.usesResolutionUniform)
    }
}

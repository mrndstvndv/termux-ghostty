package com.mrndtvndv.term.ui.workspace

import org.junit.Assert.assertEquals
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
        assertTrue(TerminalEffect.RETRO_CRT.usesTimeUniform)
        assertTrue(TerminalEffect.RETRO_CRT.usesResolutionUniform)
        assertTrue(TerminalEffect.RETRO_CRT.animated)
    }

    @Test
    fun retroCrtHasDistinctKeyAndRoundTrips() {
        assertEquals(TerminalEffect.RETRO_CRT, TerminalEffect.fromPref("retro_crt"))
        assertEquals("retro_crt", TerminalEffect.RETRO_CRT.key)
        assertEquals("Retro CRT", TerminalEffect.RETRO_CRT.label)
    }

    @Test
    fun individualRetroEffectsDeclareCorrectUniforms() {
        // static effects — no time uniform
        assertFalse(TerminalEffect.SCREEN_CURVATURE.usesTimeUniform)
        assertTrue(TerminalEffect.SCREEN_CURVATURE.usesResolutionUniform)
        assertFalse(TerminalEffect.BLOOM.usesTimeUniform)
        assertTrue(TerminalEffect.BLOOM.usesResolutionUniform)
        assertFalse(TerminalEffect.GLOWING_LINE.usesTimeUniform)
        assertFalse(TerminalEffect.GLOWING_LINE.usesResolutionUniform)
        assertFalse(TerminalEffect.CHROMATIC.usesTimeUniform)
        assertTrue(TerminalEffect.CHROMATIC.usesResolutionUniform)
        assertFalse(TerminalEffect.RGB_SHIFT.usesTimeUniform)
        assertTrue(TerminalEffect.RGB_SHIFT.usesResolutionUniform)
        // animated effects — time uniform
        assertTrue(TerminalEffect.STATIC_NOISE.usesTimeUniform)
        assertTrue(TerminalEffect.STATIC_NOISE.usesResolutionUniform)
        assertTrue(TerminalEffect.STATIC_NOISE.animated)
        assertTrue(TerminalEffect.FLICKER.usesTimeUniform)
        assertFalse(TerminalEffect.FLICKER.usesResolutionUniform)
        assertTrue(TerminalEffect.FLICKER.animated)
    }

    @Test
    fun individualRetroEffectsRoundTripViaPref() {
        assertEquals(TerminalEffect.SCREEN_CURVATURE, TerminalEffect.fromPref("curvature"))
        assertEquals(TerminalEffect.BLOOM, TerminalEffect.fromPref("bloom"))
        assertEquals(TerminalEffect.GLOWING_LINE, TerminalEffect.fromPref("glowing_line"))
        assertEquals(TerminalEffect.STATIC_NOISE, TerminalEffect.fromPref("static_noise"))
        assertEquals(TerminalEffect.CHROMATIC, TerminalEffect.fromPref("chromatic"))
        assertEquals(TerminalEffect.RGB_SHIFT, TerminalEffect.fromPref("rgb_shift"))
        assertEquals(TerminalEffect.FLICKER, TerminalEffect.fromPref("flicker"))
    }

    @Test
    fun visualEffectsUseVsyncByDefault() {
        assertEquals(VisualEffectFrameRate.VSYNC, VisualEffectFrameRate.fromPref(null))
        assertEquals(VisualEffectFrameRate.VSYNC, VisualEffectFrameRate.fromPref("invalid"))
        assertEquals(VisualEffectFrameRate.VSYNC, VisualEffectFrameRate.fromPref("display"))
    }

    @Test
    fun visualEffectFrameRateRestoresSavedCap() {
        assertEquals(VisualEffectFrameRate.FPS_120, VisualEffectFrameRate.fromPref("120"))
        assertEquals(120f, VisualEffectFrameRate.FPS_120.framesPerSecond)
    }
}

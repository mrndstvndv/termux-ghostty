package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.CursorEffect
import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalVisualEffectsTest {
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

    @Test
    fun cursorTrailPreferencesMapToRendererNeutralPresets() {
        assertEquals(CursorEffect.WARP, CursorTrailEffect.WARP.toCursorEffect())
        assertEquals(CursorEffect.SWEEP, CursorTrailEffect.SWEEP.toCursorEffect())
        assertEquals(CursorEffect.TAIL, CursorTrailEffect.TAIL.toCursorEffect())
        assertEquals(null, CursorTrailEffect.NONE.toCursorEffect())
    }
}

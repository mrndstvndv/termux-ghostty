package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.CursorEffect
import com.termux.terminal.compose.CursorEffectState
import com.termux.terminal.compose.TerminalCursor
import com.termux.terminal.compose.TerminalFrame
import com.termux.terminal.compose.internal.CursorEffectRenderPlan
import com.termux.terminal.compose.internal.planCursorEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)

class GlesCursorEffectTest {
    @Test
    fun movedCursorProducesGpuTrailAndBoundedAnimation() {
        val firstFrame = testFrame(1L).withCursor(
            TerminalCursor(0, 0, true, TerminalCursor.STYLE_BLOCK)
        )
        val movedFrame = testFrame(2L).withCursor(
            TerminalCursor(2, 0, true, TerminalCursor.STYLE_BLOCK)
        )
        val state = CursorEffectState()
        state.observe(firstFrame, 1f)
        state.observe(movedFrame, 1.01f)
        val effectSnapshot = state.snapshot(CursorEffect.SWEEP)
        val terminalSnapshot = testSnapshot(2L).copy(
            frame = movedFrame,
            cursorEffect = effectSnapshot
        )
        val plan = CursorEffectRenderPlan()

        assertTrue(
            planCursorEffect(
                effectSnapshot = terminalSnapshot.cursorEffect,
                frame = terminalSnapshot.frame,
                metrics = terminalSnapshot.metrics,
                timeSeconds = 1.05f,
                output = plan
            )
        )
        assertTrue(plan.vertexCount >= 4)
        assertEquals(20f, plan.cutoutLeft, 0.001f)
        assertEquals(30f, plan.cutoutRight, 0.001f)

        val surface = GlesTerminalSurface()
        assertTrue(surface.publish(terminalSnapshot))
        assertEquals(1.01f, surface.animationTimeSeconds(), 0f)
        surface.requestAnimationFrame(1.15f)
        assertTrue(surface.publish(terminalSnapshot.withPresentationRevision(3L)))
        assertEquals(1.15f, surface.animationTimeSeconds(), 0f)
        assertTrue(surface.needsCursorAnimationFrame(1.05f))
        assertFalse(surface.needsCursorAnimationFrame(1.30f))
        surface.release()
    }

    @Test
    fun everyCursorPresetProducesHardwareGeometry() {
        CursorEffect.entries.forEach { effect ->
            val firstFrame = testFrame(10L).withCursor(
                TerminalCursor(0, 0, true, TerminalCursor.STYLE_BLOCK)
            )
            val movedFrame = testFrame(11L).withCursor(
                TerminalCursor(2, 0, true, TerminalCursor.STYLE_BLOCK)
            )
            val state = CursorEffectState()
            state.observe(firstFrame, 2f)
            state.observe(movedFrame, 2.01f)
            val plan = CursorEffectRenderPlan()

            assertTrue(
                effect.name,
                planCursorEffect(
                    effectSnapshot = state.snapshot(effect),
                    frame = movedFrame,
                    metrics = testSnapshot(11L).metrics,
                    timeSeconds = 2.04f,
                    output = plan
                )
            )
            assertTrue(effect.name, plan.vertexCount >= 4)
        }
    }

    @Test
    fun discontinuousFrameSequenceDoesNotProduceGpuTrail() {
        val state = CursorEffectState()
        state.observe(
            testFrame(1L).withCursor(TerminalCursor(0, 0, true, TerminalCursor.STYLE_BLOCK)),
            1f
        )
        state.observe(
            testFrame(3L).withCursor(TerminalCursor(2, 0, true, TerminalCursor.STYLE_BLOCK)),
            1.01f
        )

        assertEquals(null, state.snapshot(CursorEffect.SWEEP))
    }
}

private fun TerminalFrame.withCursor(cursor: TerminalCursor): TerminalFrame = TerminalFrame(
    sequence = sequence,
    viewport = viewport,
    cursor = cursor,
    modes = modes,
    palette = palette,
    rows = rows,
    linkLayout = linkLayout
)

package com.mrndtvndv.term.ui.workspace

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugHudTest {
    @Test
    fun countsMissedDeadlinesFromPresentedFrameDuration() {
        val tracker = FrameMetricsAccumulator(refreshRate = 60f)

        tracker.recordPresentedFrame(50_000_000L)
        val sample = tracker.snapshot(nowNanos = 1_000_000_000L)

        assertEquals(2, sample.missedFrames)
    }

    @Test
    fun countsPresentedFramesWithoutDrivingTheComposeFrameClock() {
        val tracker = FrameMetricsAccumulator(refreshRate = 60f)

        tracker.recordPresentedFrame(8_000_000L)
        tracker.recordPresentedFrame(8_000_000L)
        val sample = tracker.snapshot(nowNanos = 1_000_000_000L)

        assertEquals(2f, sample.framesPerSecond)
        assertEquals(0, sample.missedFrames)
    }
}

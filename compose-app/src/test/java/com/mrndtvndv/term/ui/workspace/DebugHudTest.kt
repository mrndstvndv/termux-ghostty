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
    fun ignoresIdleFrameClockGapsInsteadOfReportingMissedFrames() {
        val tracker = FrameMetricsAccumulator(refreshRate = 60f)

        tracker.record(1_000_000_000L)
        tracker.record(1_900_000_000L)
        val sample = tracker.snapshot(nowNanos = 2_000_000_000L)

        assertEquals(0, sample.missedFrames)
    }
}

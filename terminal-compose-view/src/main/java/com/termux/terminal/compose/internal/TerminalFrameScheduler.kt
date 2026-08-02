package com.termux.terminal.compose.internal

import android.view.Choreographer

/**
 * Coalesced, main-thread frame driver.
 *
 * [start] runs a continuous loop (continuous shaders); [requestFrame] wakes a
 * single frame when no loop is running (cursor-effect pulses). Frames are
 * coalesced so rapid invalidations collapse into one draw. [setFrameIntervalNanos]
 * clamps the continuous loop to a consumer-requested frame rate.
 */
internal class TerminalFrameScheduler(
    private val onFrame: (timeSeconds: Float) -> Unit
) {
    private val choreographer = Choreographer.getInstance()
    private var scheduled = false
    private var continuous = false
    private var frameIntervalNanos = 0L
    private var lastEmittedNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            scheduled = false
            val now = System.nanoTime()
            if (continuous) {
                if (frameIntervalNanos > 0L && now - lastEmittedNanos < frameIntervalNanos) {
                    postFrame()
                    return
                }
                lastEmittedNanos = now
            }
            onFrame(frameTimeNanos / NANOS_PER_SECOND)
            if (continuous) {
                postFrame()
            }
        }
    }

    /** Starts a continuous frame loop; idempotent. */
    fun start() {
        if (continuous) return
        continuous = true
        postFrame()
    }

    /** Stops the continuous loop, if any. */
    fun stop() {
        continuous = false
    }

    /** Wakes a single coalesced frame; no-op while the loop is running. */
    fun requestFrame() {
        if (continuous) return
        postFrame()
    }

    /** Clamps the continuous loop to at most one frame per [intervalNanos]. */
    fun setFrameIntervalNanos(intervalNanos: Long) {
        frameIntervalNanos = intervalNanos
    }

    private fun postFrame() {
        if (scheduled) return
        scheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}

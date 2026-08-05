package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.TerminalFrame

/** Builds at most one immutable terminal frame for a burst of backend invalidations. */
internal class LazyTerminalFrameCache(
    private val buildFrame: () -> TerminalFrame?
) {
    private var frame: TerminalFrame? = null
    private var dirty = true

    fun invalidate() {
        dirty = true
    }

    fun currentFrame(): TerminalFrame? {
        if (!dirty) return frame

        val nextFrame = buildFrame() ?: return frame
        frame = nextFrame
        dirty = false
        return nextFrame
    }

    fun cachedFrame(): TerminalFrame? = frame

    fun clear() {
        frame = null
        dirty = false
    }
}

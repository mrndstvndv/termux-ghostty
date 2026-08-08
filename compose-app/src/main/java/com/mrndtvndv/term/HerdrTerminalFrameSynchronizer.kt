package com.mrndtvndv.term

/**
 * Turns an out-of-band Herdr focus command into an explicit terminal-frame
 * publication. Herdr runs through a separate SSH exec channel, so an idle
 * interactive terminal cannot rely on output activity to schedule a frame.
 */
internal class HerdrTerminalFrameSynchronizer(
    private val requestFrameRefresh: (serverId: String) -> Unit
) {
    suspend fun focus(serverId: String, operation: suspend () -> Boolean): Boolean {
        val focused = operation()
        if (focused) requestFrameRefresh(serverId)
        return focused
    }
}

package com.mrndtvndv.term

import org.junit.Assert.assertEquals
import org.junit.Test

class ActiveTerminalFrameRouterTest {
    @Test
    fun `idle session binding replays once and ignores stale session callbacks`() {
        val refreshes = mutableListOf<String>()
        val router = ActiveTerminalFrameRouter<String, String> { backend: String -> refreshes += backend }

        router.bind(session = "session-a", backend = "backend-a")
        router.onFrameAvailable("session-b")
        router.bind(session = "session-b", backend = "backend-b")
        router.onFrameAvailable("session-a")
        router.onFrameAvailable("session-b")

        assertEquals(listOf("backend-a", "backend-b", "backend-b"), refreshes)
    }

    @Test
    fun `stale disposal cannot unbind the replacement backend`() {
        val refreshes = mutableListOf<String>()
        val router = ActiveTerminalFrameRouter<String, String> { backend: String -> refreshes += backend }

        router.bind(session = "session-a", backend = "backend-a")
        router.bind(session = "session-b", backend = "backend-b")
        router.unbind(session = "session-a", backend = "backend-a")
        router.refreshActive()

        assertEquals(listOf("backend-a", "backend-b", "backend-b"), refreshes)
    }
}

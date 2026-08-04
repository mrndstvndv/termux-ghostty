package com.mrndtvndv.term.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionFinishedEventsTest {
    @Test
    fun `finish event is delivered after the collector starts`() = runTest {
        val events = SessionFinishedEvents()

        events.emit("server-1")

        assertEquals("server-1", events.flow.first())
    }
}

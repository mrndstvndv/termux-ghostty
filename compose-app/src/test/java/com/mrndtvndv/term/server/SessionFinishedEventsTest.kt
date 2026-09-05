package com.mrndtvndv.term.server

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun `burst disconnect emits are all delivered to an active collector`() = runTest {
        val events = SessionFinishedEvents()
        val ids = listOf("s1", "s2", "s3", "s4", "s5")
        val collected = async {
            events.flow.take(5).toList()
        }
        runCurrent()

        ids.forEach { events.emit(it) }

        assertEquals(ids, collected.await())
    }
}

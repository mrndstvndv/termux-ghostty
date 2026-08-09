package com.mrndtvndv.term.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalProgressStoreTest {
    @Test
    fun updatePublishesLatestSnapshotToSessionObservers() {
        val store = TerminalProgressStore()
        val observed = store.observe(sessionHandle = "session", initial = null)
        val progress = TerminalProgress(state = 1, value = 50, generation = 4)

        store.update(sessionHandle = "session", progress = progress)

        assertEquals(progress, observed.value)
    }

    @Test
    fun removeClearsExistingObservers() {
        val store = TerminalProgressStore()
        val observed = store.observe(sessionHandle = "session", initial = null)
        store.update(
            sessionHandle = "session",
            progress = TerminalProgress(state = 1, value = 50, generation = 4),
        )

        store.remove(sessionHandle = "session")

        assertNull(observed.value)
    }
}

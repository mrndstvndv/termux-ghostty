package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSnapshotHandoffTest {
    @Test
    fun publicationIsLatestWinsAndConsumesOnce() {
        val handoff = TerminalSnapshotHandoff()

        assertTrue(handoff.publish(testSnapshot(1L)))
        assertTrue(handoff.publish(testSnapshot(2L)))

        assertEquals(2L, handoff.acquire()!!.frame.sequence)
        assertNull(handoff.acquire())
    }

    @Test
    fun releaseBeforeConsumeDropsPendingAndRejectsFuturePublications() {
        val handoff = TerminalSnapshotHandoff()
        handoff.publish(testSnapshot(1L))

        handoff.release()
        handoff.release()

        assertNull(handoff.acquire())
        assertFalse(handoff.publish(testSnapshot(2L)))
        assertFalse(handoff.hasPending())
    }

    @Test
    fun aConsumedSnapshotRemainsImmutableToTheHandoff() {
        val handoff = TerminalSnapshotHandoff()
        val snapshot = testSnapshot(7L)

        assertTrue(handoff.publish(snapshot))
        assertTrue(handoff.acquire() === snapshot)
        assertEquals(7L, snapshot.presentationRevision)
    }

    @Test
    fun olderPresentationOrTerminalRevisionsCannotReplaceTheLatestPublication() {
        val handoff = TerminalSnapshotHandoff()
        val latest = testSnapshot(7L).copy(presentationRevision = 10L)

        assertTrue(handoff.publish(latest))
        assertFalse(
            handoff.publish(
                testSnapshot(6L).copy(presentationRevision = 11L)
            )
        )
        assertFalse(
            handoff.publish(
                testSnapshot(8L).copy(presentationRevision = 10L)
            )
        )
        assertTrue(handoff.acquire() === latest)
    }
}

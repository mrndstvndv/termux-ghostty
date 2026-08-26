package com.termux.terminal.compose.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GlesTerminalSurfaceTest {
    @Test
    fun publicSurfaceReleaseIsIdempotentAndRejectsLaterSnapshots() {
        val surface = GlesTerminalSurface()

        assertTrue(surface.publish(testSnapshot(31L)))
        surface.release()
        surface.release()

        assertFalse(surface.publish(testSnapshot(32L)))
    }

    @Test
    fun retainedSnapshotSurvivesAConsumedPublication() {
        val surface = GlesTerminalSurface()
        val snapshot = testSnapshot(41L)

        assertTrue(surface.publish(snapshot))
        assertTrue(surface.acquireSnapshot() === snapshot)
        assertTrue(surface.acquireSnapshot() === snapshot)

        surface.release()
    }

    @Test
    fun aNewSurfaceAcceptsASequenceRestartFromANewTerminalOwner() {
        val oldSurface = GlesTerminalSurface()
        assertTrue(oldSurface.publish(testSnapshot(100L)))
        oldSurface.release()

        val newSurface = GlesTerminalSurface()
        assertTrue(newSurface.publish(testSnapshot(1L)))
        newSurface.release()
    }
}

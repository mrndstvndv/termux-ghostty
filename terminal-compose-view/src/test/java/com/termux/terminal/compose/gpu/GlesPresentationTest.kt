package com.termux.terminal.compose.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesPresentationTest {
    @Test
    fun identicalCallbackIsClassifiedRedundantButResetStillRequiresPresentation() {
        val snapshot = testSnapshot(1L)

        val redundant = glesPresentationDecision(
                snapshot = snapshot,
                presentedSnapshot = snapshot,
                animationTime = 2f,
                lastAnimationTime = 2f,
                atlasReset = false
            )
        assertTrue(redundant.redundant)
        assertTrue(redundant.requiresCompleteFramebuffer)

        val reset = glesPresentationDecision(
                snapshot = snapshot,
                presentedSnapshot = snapshot,
                animationTime = 2f,
                lastAnimationTime = 2f,
                atlasReset = true
            )
        assertFalse(reset.redundant)
        assertTrue(reset.requiresCompleteFramebuffer)
    }

    @Test
    fun newSnapshotOrAnimationTickIsNotRedundant() {
        val first = testSnapshot(1L)
        val second = testSnapshot(2L)

        assertFalse(
            glesPresentationDecision(
                snapshot = second,
                presentedSnapshot = first,
                animationTime = 1f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
        assertFalse(
            glesPresentationDecision(
                snapshot = first,
                presentedSnapshot = first,
                animationTime = 2f,
                lastAnimationTime = 1f,
                atlasReset = false
            ).redundant
        )
    }
}

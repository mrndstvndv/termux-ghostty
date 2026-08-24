package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GlesDirtyInstanceBufferTest {
    @Test
    fun fullRowDamageOrphansStorageWithoutCloningIt() {
        assertEquals(
            GlesDirtyBufferUpdateStrategy.ORPHAN_STORAGE,
            glesDirtyBufferUpdateStrategy(
                geometryChanged = false,
                changedRowCount = 40,
                rowCount = 40
            )
        )
    }

    @Test
    fun sparseRowDamagePreservesUnchangedStorage() {
        assertEquals(
            GlesDirtyBufferUpdateStrategy.CLONE_STORAGE,
            glesDirtyBufferUpdateStrategy(
                geometryChanged = false,
                changedRowCount = 1,
                rowCount = 40
            )
        )
    }

    @Test
    fun geometryChangeUsesFreshStorage() {
        assertEquals(
            GlesDirtyBufferUpdateStrategy.FRESH_STORAGE,
            glesDirtyBufferUpdateStrategy(
                geometryChanged = true,
                changedRowCount = 40,
                rowCount = 40
            )
        )
    }
}

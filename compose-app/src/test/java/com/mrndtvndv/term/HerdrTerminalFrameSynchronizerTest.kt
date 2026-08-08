package com.mrndtvndv.term

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrTerminalFrameSynchronizerTest {
    @Test
    fun `successful idle focus requests a fresh terminal frame`() = runTest {
        val refreshes = mutableListOf<String>()
        val synchronizer = HerdrTerminalFrameSynchronizer { serverId -> refreshes += serverId }

        val focused = synchronizer.focus("server-a") { true }

        assertTrue(focused)
        assertEquals(listOf("server-a"), refreshes)
    }

    @Test
    fun `failed focus does not publish a misleading frame`() = runTest {
        val refreshes = mutableListOf<String>()
        val synchronizer = HerdrTerminalFrameSynchronizer { serverId -> refreshes += serverId }

        val focused = synchronizer.focus("server-a") { false }

        assertFalse(focused)
        assertTrue(refreshes.isEmpty())
    }
}

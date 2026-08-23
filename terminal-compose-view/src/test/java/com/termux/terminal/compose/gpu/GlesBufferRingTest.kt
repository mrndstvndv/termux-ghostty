package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GlesBufferRingTest {
    @Test
    fun ledgerKeepsAtMostThreeSlotsInFlightAndReusesRetiredSlots() {
        val ledger = GlesBufferRingLedger(slotCount = 3)

        assertEquals(0, ledger.acquire())
        assertEquals(1, ledger.acquire())
        assertEquals(2, ledger.acquire())
        assertEquals(-1, ledger.acquire())
        assertEquals(3, ledger.inFlightCount())

        ledger.retire(1)

        assertEquals(1, ledger.acquire())
        assertEquals(3, ledger.inFlightCount())
    }

    @Test
    fun resetClearsTheBoundedInFlightLedger() {
        val ledger = GlesBufferRingLedger(slotCount = 3)
        ledger.acquire()
        ledger.acquire()

        ledger.reset()

        assertEquals(0, ledger.inFlightCount())
        assertEquals(0, ledger.acquire())
    }
}

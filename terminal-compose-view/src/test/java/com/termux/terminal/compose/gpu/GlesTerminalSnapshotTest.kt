package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.ShaderDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesTerminalSnapshotTest {
    @Test
    fun snapshotCarriesCompleteFrameAndIndependentVisualShaderList() {
        val source = mutableListOf(ShaderDefinition("agsl", "half4 main(float2 p) { return half4(1); }"))
        val visual = GlesTerminalVisualConfig(agslShaders = source)
        val snapshot = testSnapshot(21L).copy(visual = visual)
        source.clear()

        assertEquals(1, snapshot.visual.agslShaders.size)
        assertEquals(21L, snapshot.contentRevision)
        assertEquals(21L, snapshot.presentationRevision)
        assertTrue(snapshot.frame.rows.size == snapshot.frame.rowsVisible)
        assertNotSame(source, snapshot.visual.agslShaders)
    }

    @Test
    fun presentationRevisionCanAdvanceWithoutChangingTerminalSequence() {
        val snapshot = testSnapshot(22L)

        val next = snapshot.withPresentationRevision(23L)

        assertEquals(22L, next.frame.sequence)
        assertEquals(23L, next.presentationRevision)
    }
}

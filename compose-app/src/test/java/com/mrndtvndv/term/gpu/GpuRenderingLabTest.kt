package com.mrndtvndv.term.gpu

import com.termux.terminal.compose.TerminalBackendError
import com.termux.terminal.compose.TerminalBackendListener
import com.termux.terminal.compose.TerminalSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuRenderingLabTest {

    @Test
    fun sceneMatrixHasUniqueIdsAndVisibleInvariants() {
        val scenes = GpuLabScenes.all

        assertTrue(scenes.size >= 10)
        assertEquals(scenes.size, scenes.map { it.id }.toSet().size)
        assertTrue(scenes.all { it.expectedInvariants.isNotEmpty() })
        assertTrue(scenes.any { it.id == "unicode-wide-emoji" })
        assertTrue(scenes.any { it.id == "atlas-churn" })
        assertTrue(scenes.any { it.id == "odd-resize" })
    }

    @Test
    fun backendPublishesCompleteMonotonicFrames() {
        val backend = FakeTerminalBackend()
        val sequences = mutableListOf<Long>()
        backend.attach(RecordingListener { sequences += backend.currentFrame()!!.sequence })

        repeat(4) {
            backend.step()
        }

        assertTrue(sequences.zipWithNext().all { (before, after) -> after > before })
        val frame = backend.currentFrame()!!
        assertEquals(frame.rowsVisible, frame.rows.size)
        assertTrue(frame.rows.all { it.columns == frame.columns })
        assertTrue(frame.rows.all { it.text().size >= it.charsUsed })
    }

    @Test
    fun resizeRebuildsGeometryWithoutDroppingRows() {
        val backend = FakeTerminalBackend()
        val before = backend.currentFrame()!!.sequence
        backend.resize(
            TerminalSize(
                widthPx = 641,
                heightPx = 479,
                columns = 17,
                rows = 9,
                cellWidthPx = 37,
                cellHeightPx = 53,
                contentTopPx = 3
            )
        )

        val frame = backend.currentFrame()!!
        assertTrue(frame.sequence > before)
        assertEquals(17, frame.columns)
        assertEquals(9, frame.rowsVisible)
        assertEquals(9, frame.rows.size)
        assertTrue(frame.rows.all { it.columns == 17 })
        assertEquals(641, backend.snapshot().size.widthPx)
        assertEquals(3, backend.snapshot().size.contentTopPx)
    }

    @Test
    fun checksumContractMatchesTheFinalFrameAcrossTransitions() {
        val backend = FakeTerminalBackend()
        assertChecksumContract(backend)
        assertTrue(!backend.snapshot().sentinel.contains("exp="))

        backend.resize(
            TerminalSize(
                widthPx = 641,
                heightPx = 479,
                columns = 17,
                rows = 9,
                cellWidthPx = 37,
                cellHeightPx = 53,
                contentTopPx = 3
            )
        )
        assertChecksumContract(backend)

        repeat(3) {
            backend.step()
            assertChecksumContract(backend)
        }
        GpuLabScenes.all.indices.forEach { sceneIndex ->
            backend.selectScene(sceneIndex)
            assertChecksumContract(backend)
        }
    }

    @Test
    fun publicationPairRemainsCurrentAcrossResizeStepAndSceneChanges() {
        val backend = FakeTerminalBackend()
        assertPublicationContract(backend)
        backend.resize(
            TerminalSize(
                widthPx = 641,
                heightPx = 479,
                columns = 17,
                rows = 9,
                cellWidthPx = 37,
                cellHeightPx = 53,
                contentTopPx = 3
            )
        )
        assertPublicationContract(backend)
        repeat(3) {
            backend.step()
            assertPublicationContract(backend)
        }
        GpuLabScenes.all.indices.forEach { sceneIndex ->
            backend.selectScene(sceneIndex)
            assertPublicationContract(backend)
        }
    }

    @Test(expected = IllegalStateException::class)
    fun publicationContractRejectsAStaleFrame() {
        val backend = FakeTerminalBackend()
        val state = backend.snapshot()
        requireGpuLabPublication(state.copy(sequence = state.sequence + 1), backend.currentFrame()!!)
    }

    @Test
    fun sparseSceneKeepsStableRowsAndChangesOnlyProgressRow() {
        val backend = FakeTerminalBackend()
        backend.selectScene(GpuLabScenes.all.indexOfFirst { it.id == "sparse-update" })
        val before = backend.currentFrame()!!
        backend.step()
        val after = backend.currentFrame()!!

        assertEquals(before.rows[0].contentHash, after.rows[0].contentHash)
        assertEquals(before.rows[1].contentHash, after.rows[1].contentHash)
        assertEquals(before.rows[2].contentHash, after.rows[2].contentHash)
        assertNotEquals(before.rows[3].contentHash, after.rows[3].contentHash)
        assertEquals(before.rows[4].contentHash, after.rows[4].contentHash)
    }

    @Test
    fun unicodeSceneContainsWideCellsAndLinkSceneContainsHitTargets() {
        val backend = FakeTerminalBackend()
        backend.selectScene(GpuLabScenes.all.indexOfFirst { it.id == "unicode-wide-emoji" })
        val unicodeFrame = backend.currentFrame()!!
        assertTrue(
            unicodeFrame.rows.any { row ->
                (0 until row.columns).any { column -> row.cellDisplayWidth(column) == 2 }
            }
        )

        backend.selectScene(GpuLabScenes.all.indexOfFirst { it.id == "cursor-selection-links" })
        val linkFrame = backend.currentFrame()!!
        val linkLayout = linkFrame.linkLayout
        assertNotNull(linkLayout)
        assertNotNull(linkLayout!!.findAt(linkFrame.topRow + 3, 10))
    }

    @Test
    fun cursorScenePublishesSingleMultiRowAndWideSelections() {
        val backend = FakeTerminalBackend()
        backend.selectScene(GpuLabScenes.all.indexOfFirst { it.id == "cursor-selection-links" })
        val selections = buildList {
            repeat(3) {
                add(backend.snapshot().selection)
                backend.step()
            }
        }

        assertTrue(selections.all { !it.isEmpty })
        assertEquals(selections[0].startRow, selections[0].endRow)
        assertEquals(selections[0].startCol, selections[0].endCol)
        assertTrue(selections[1].endRow > selections[1].startRow)
        assertEquals(1, selections[2].endCol - selections[2].startCol)
    }

    private class RecordingListener(
        private val onFrame: () -> Unit
    ) : TerminalBackendListener {
        override fun onFrameInvalidated() = onFrame()

        override fun onBackendError(error: TerminalBackendError) = Unit
    }

    private fun assertChecksumContract(backend: FakeTerminalBackend) {
        val frame = backend.currentFrame()!!
        val state = backend.snapshot()
        assertEquals(checksumForFrame(frame), state.checksum)
        assertEquals(state.checksum, state.expectedChecksum)
    }

    private fun assertPublicationContract(backend: FakeTerminalBackend) {
        requireGpuLabPublication(backend.snapshot(), backend.currentFrame()!!)
    }
}

package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GlesGlyphBatchingTest {
    @Test
    fun batchesSeparateAtlasPagesAndGenerations() {
        val batcher = GlesGlyphBatchAccumulator(maxQuadsPerBatch = 8, maxActiveBatches = 3)

        assertNull(batcher.add(resolvedGlyph(page = 0, generation = 1, text = "a")))
        assertNull(batcher.add(resolvedGlyph(page = 1, generation = 1, text = "b")))
        assertNull(batcher.add(resolvedGlyph(page = 0, generation = 2, text = "c")))

        val batches = batcher.flush()

        assertEquals(
            listOf(
                GlesGlyphBatchKey(pageIndex = 0, atlasGeneration = 1),
                GlesGlyphBatchKey(pageIndex = 1, atlasGeneration = 1),
                GlesGlyphBatchKey(pageIndex = 0, atlasGeneration = 2)
            ),
            batches.map { it.key }
        )
        assertEquals(0, batcher.pendingBatchCount())
    }

    @Test
    fun fullBatchIsEmittedWithoutGrowingPastTheSubmissionBound() {
        val batcher = GlesGlyphBatchAccumulator(maxQuadsPerBatch = 2, maxActiveBatches = 1)

        assertNull(batcher.add(resolvedGlyph(page = 0, generation = 1, text = "a")))
        val full = batcher.add(resolvedGlyph(page = 0, generation = 1, text = "b"))

        assertNotNull(full)
        assertEquals(2, full!!.glyphs.size)
        assertEquals(0, batcher.pendingBatchCount())
    }

    @Test
    fun resetBoundaryDrainsOldGenerationBeforeNewBatchesAreQueued() {
        val batcher = GlesGlyphBatchAccumulator(maxQuadsPerBatch = 8, maxActiveBatches = 2)
        batcher.add(resolvedGlyph(page = 0, generation = 4, text = "old"))

        val beforeReset = batcher.flush()

        assertEquals(4, beforeReset.single().key.atlasGeneration)
        assertEquals(0, batcher.pendingBatchCount())
        batcher.add(resolvedGlyph(page = 0, generation = 5, text = "new"))
        assertEquals(5, batcher.flush().single().key.atlasGeneration)
    }

    private fun resolvedGlyph(page: Int, generation: Int, text: String): GlesResolvedGlyph {
        val region = GlyphAtlasRegion(
            pageIndex = page,
            left = 0,
            top = 0,
            width = 8,
            height = 8,
            atlasGeneration = generation
        )
        return GlesResolvedGlyph(
            placement = TerminalGlyphPlacement(atlasKey(text), 0f, 0f),
            region = region
        )
    }
}

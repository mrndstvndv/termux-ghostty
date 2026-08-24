package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Test

class GlesGlyphBatchingTest {
    @Test
    fun reusesResolvedGlyphForAnUnchangedPlacement() {
        val cache = GlesResolvedGlyphCache(maxEntries = 2)
        val placement = TerminalGlyphPlacement(atlasKey("a"), 0f, 0f)
        val region = GlyphAtlasRegion(0, 0, 0, 8, 8, 1)

        assertSame(cache.resolve(placement, region), cache.resolve(placement, region))
    }

    @Test
    fun batchesCombineAtlasPagesAndSeparateGenerations() {
        val batcher = GlesGlyphBatchAccumulator(maxQuadsPerBatch = 8, maxActiveBatches = 2)

        assertNull(
            batcher.add(
                resolvedGlyph(
                    page = 0,
                    generation = 1,
                    text = "a",
                    rasterMode = GlyphAtlasKey.RASTER_MODE_MASK
                )
            )
        )
        assertNull(batcher.add(resolvedGlyph(page = 1, generation = 1, text = "b")))
        assertNull(batcher.add(resolvedGlyph(page = 0, generation = 2, text = "c")))

        val batches = batcher.flush()

        assertEquals(
            listOf(GlesGlyphBatchKey(atlasGeneration = 1), GlesGlyphBatchKey(atlasGeneration = 2)),
            batches.map { it.key }
        )
        assertEquals(listOf(2, 1), batches.map { it.glyphs.size })
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

    private fun resolvedGlyph(
        page: Int,
        generation: Int,
        text: String,
        rasterMode: Int = GlyphAtlasKey.RASTER_MODE_RGBA
    ): GlesResolvedGlyph {
        val region = GlyphAtlasRegion(
            pageIndex = page,
            left = 0,
            top = 0,
            width = 8,
            height = 8,
            atlasGeneration = generation,
            rasterMode = rasterMode
        )
        return GlesResolvedGlyph(
            placement = TerminalGlyphPlacement(atlasKey(text), 0f, 0f),
            region = region
        )
    }
}

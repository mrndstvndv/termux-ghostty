package com.termux.terminal.compose.gpu

internal data class GlesGlyphBatchKey(
    val pageIndex: Int,
    val atlasGeneration: Int
)

internal data class GlesResolvedGlyph(
    val placement: TerminalGlyphPlacement,
    val region: GlyphAtlasRegion
) {
    val batchKey: GlesGlyphBatchKey
        get() = GlesGlyphBatchKey(region.pageIndex, region.atlasGeneration)
}

internal data class GlesGlyphBatch(
    val key: GlesGlyphBatchKey,
    val glyphs: List<GlesResolvedGlyph>
)

/**
 * Groups bounded glyph submissions by the texture and atlas generation they
 * reference. A reset boundary drains this accumulator before old textures are
 * deleted, so no batch can retain a stale region.
 */
internal class GlesGlyphBatchAccumulator(
    private val maxQuadsPerBatch: Int,
    private val maxActiveBatches: Int
) {
    private class MutableBatch(val key: GlesGlyphBatchKey) {
        val glyphs = ArrayList<GlesResolvedGlyph>()
    }

    private val pending = ArrayList<MutableBatch>(maxActiveBatches)

    init {
        require(maxQuadsPerBatch >= 1) { "maxQuadsPerBatch must be positive" }
        require(maxActiveBatches >= 1) { "maxActiveBatches must be positive" }
    }

    /** Returns a full batch when this key reaches the bounded submission size. */
    fun add(glyph: GlesResolvedGlyph): GlesGlyphBatch? {
        val batch = pending.firstOrNull { it.key == glyph.batchKey }
            ?: MutableBatch(glyph.batchKey).also {
                check(pending.size < maxActiveBatches) {
                    "active atlas batch count exceeded its bound"
                }
                pending += it
            }
        batch.glyphs += glyph
        if (batch.glyphs.size < maxQuadsPerBatch) return null
        pending.remove(batch)
        return batch.toBatch()
    }

    /** Flushes every pending texture/generation batch at a reset boundary. */
    fun flush(): List<GlesGlyphBatch> {
        if (pending.isEmpty()) return emptyList()
        val flushed = pending.map { it.toBatch() }
        pending.clear()
        return flushed
    }

    fun pendingBatchCount(): Int = pending.size

    private fun MutableBatch.toBatch(): GlesGlyphBatch =
        GlesGlyphBatch(key, glyphs.toList())
}

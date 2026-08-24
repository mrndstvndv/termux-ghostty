package com.termux.terminal.compose.gpu

import java.util.ArrayDeque
import java.util.IdentityHashMap

internal data class GlesGlyphBatchKey(
    val pageIndex: Int,
    val atlasGeneration: Int,
    val rasterMode: Int = GlyphAtlasKey.RASTER_MODE_RGBA
)

internal data class GlesResolvedGlyph(
    val placement: TerminalGlyphPlacement,
    val region: GlyphAtlasRegion
) {
    val batchKey: GlesGlyphBatchKey
        get() = GlesGlyphBatchKey(
            pageIndex = region.pageIndex,
            atlasGeneration = region.atlasGeneration,
            rasterMode = region.rasterMode
        )
}

internal data class GlesGlyphBatch(
    val key: GlesGlyphBatchKey,
    val glyphs: List<GlesResolvedGlyph>
)

/** Reuses resolved pair objects while bounding references to changed row packets. */
internal class GlesResolvedGlyphCache(private val maxEntries: Int = 8192) {
    private val entries = IdentityHashMap<TerminalGlyphPlacement, GlesResolvedGlyph>()
    private val insertionOrder = ArrayDeque<TerminalGlyphPlacement>()

    init {
        require(maxEntries >= 1) { "maxEntries must be positive" }
    }

    fun resolve(placement: TerminalGlyphPlacement, region: GlyphAtlasRegion): GlesResolvedGlyph {
        val cached = entries[placement]
        if (cached != null && cached.region == region) return cached
        if (cached != null) {
            return GlesResolvedGlyph(placement, region).also { entries[placement] = it }
        }
        if (entries.size >= maxEntries) {
            entries.remove(insertionOrder.removeFirst())
        }
        return GlesResolvedGlyph(placement, region).also {
            entries[placement] = it
            insertionOrder.addLast(placement)
        }
    }

    fun clear() {
        entries.clear()
        insertionOrder.clear()
    }
}

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

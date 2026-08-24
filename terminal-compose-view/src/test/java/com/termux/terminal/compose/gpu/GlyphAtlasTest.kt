package com.termux.terminal.compose.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphAtlasTest {
    @Test
    fun maskGlyphsShareOneAtlasEntryAcrossForegroundColors() {
        val allocator = GlyphAtlasAllocator(GlyphAtlasLimits(pageSizePx = 64, maxPages = 1))
        val red = atlasKey("A").copy(
            foregroundArgb = 0xFFFF0000.toInt(),
            rasterMode = GlyphAtlasKey.RASTER_MODE_MASK
        )
        val blue = red.copy(foregroundArgb = 0xFF0000FF.toInt())

        val first = allocator.allocateNew(red, 8, 8)!!.region

        assertEquals(first, allocator.find(blue))
        assertEquals(1, allocator.diagnostics().entryCount)
    }

    @Test
    fun unicodeTextIsPartOfTheAtlasKeyWithoutCodeUnitCollapse() {
        val allocator = GlyphAtlasAllocator(GlyphAtlasLimits(pageSizePx = 64, maxPages = 1))
        val ascii = atlasKey("A")
        val emoji = atlasKey("🧠")
        val combining = atlasKey("e\u0301")

        assertNotEquals(ascii, emoji)
        assertNotEquals(emoji, combining)
        assertNotNull(allocator.allocateNew(ascii, 8, 8))
        assertNotNull(allocator.allocateNew(emoji, 8, 8))
        assertNotNull(allocator.allocateNew(combining, 8, 8))
        assertEquals(3, allocator.diagnostics().entryCount)
    }

    @Test
    fun allocatorStaysWithinPagesAndResetsWithAStaleGeneration() {
        val limits = GlyphAtlasLimits(
            pageSizePx = 32,
            maxPages = 1,
            maxEntries = 16,
            paddingPx = 1,
            maxGlyphWidthPx = 16,
            maxGlyphHeightPx = 16
        )
        val allocator = GlyphAtlasAllocator(limits)
        val first = allocator.allocateNew(atlasKey("0"), 16, 16)!!.region
        allocator.allocateNew(atlasKey("1"), 16, 16)
        allocator.allocateNew(atlasKey("2"), 16, 16)
        allocator.allocateNew(atlasKey("3"), 16, 16)
        val reset = allocator.allocateNew(atlasKey("4"), 16, 16)!!

        assertTrue(reset.reset)
        assertFalse(allocator.isCurrent(first))
        assertTrue(allocator.isCurrent(reset.region))
        assertTrue(reset.region.right <= limits.pageSizePx)
        assertTrue(reset.region.bottom <= limits.pageSizePx)
        assertEquals(1L, allocator.diagnostics().resetCount)
    }

    @Test
    fun resetDropsCachedKeysBeforeARegionCanBeReused() {
        val allocator = GlyphAtlasAllocator(GlyphAtlasLimits(pageSizePx = 64, maxPages = 1))
        val key = atlasKey("reused")
        val first = allocator.allocateNew(key, 8, 8)!!.region

        allocator.reset()

        assertNull(allocator.find(key))
        val second = allocator.allocateNew(key, 8, 8)!!.region
        assertNotEquals(first.atlasGeneration, second.atlasGeneration)
        assertTrue(allocator.isCurrent(second))
        assertFalse(allocator.isCurrent(first))
    }

    @Test
    fun cacheHitsAndMissesRemainBoundedCounters() {
        val allocator = GlyphAtlasAllocator(GlyphAtlasLimits(pageSizePx = 64))
        val key = atlasKey("界")

        assertNull(allocator.find(key))
        allocator.allocateNew(key, 8, 8)
        assertNotNull(allocator.find(key))

        val diagnostics = allocator.diagnostics()
        assertEquals(1L, diagnostics.cacheHits)
        assertEquals(1L, diagnostics.cacheMisses)
    }

    @Test
    fun physicalAtlasPagesFitWithinTheReportedTextureLimit() {
        listOf(32, 64, 1000, 16384).forEach { maxTextureSize ->
            val limits = GlyphAtlasLimits.forGlMaxTextureSize(maxTextureSize)

            assertTrue(limits.pageSizePx * limits.maxPages <= maxTextureSize)
        }
    }

    @Test
    fun rasterGeometryKeepsSkewedInkInsideTheAtlasAndPreservesItsBearing() {
        val geometry = glyphRasterGeometry(
            bounds = GlyphPaintBounds(-2, -12, 10, 2),
            measuredWidth = 9f,
            cellHeightPx = 16f,
            fontAscentPx = -12f,
            paddingPx = 1
        )

        assertEquals(14, geometry.width)
        assertEquals(16, geometry.height)
        assertEquals(-3f, geometry.drawOffsetX, 0f)
        assertEquals(-1f, geometry.drawOffsetY, 0f)
        assertEquals(3f, geometry.drawOriginX, 0f)
        assertEquals(13f, geometry.drawBaselineY, 0f)
    }
}

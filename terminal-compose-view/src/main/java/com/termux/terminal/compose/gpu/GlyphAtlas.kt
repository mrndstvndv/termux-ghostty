package com.termux.terminal.compose.gpu

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import kotlin.math.ceil
import java.util.LinkedHashMap

internal const val GlesItalicTextSkewX = -0.35f

/** Hard bounds for one RGBA glyph atlas. */
data class GlyphAtlasLimits(
    val pageSizePx: Int = 512,
    val maxPages: Int = 2,
    val maxEntries: Int = 2048,
    val paddingPx: Int = 1,
    val maxGlyphWidthPx: Int = minOf(256, pageSizePx),
    val maxGlyphHeightPx: Int = minOf(128, pageSizePx)
) {
    init {
        require(pageSizePx >= 32) { "pageSizePx must be at least 32" }
        require(maxPages in 1..8) { "maxPages must be between 1 and 8" }
        require(maxEntries >= 1) { "maxEntries must be positive" }
        require(paddingPx >= 0) { "paddingPx must not be negative" }
        require(maxGlyphWidthPx > paddingPx * 2) { "maxGlyphWidthPx leaves no glyph area" }
        require(maxGlyphHeightPx > paddingPx * 2) { "maxGlyphHeightPx leaves no glyph area" }
        require(maxGlyphWidthPx <= pageSizePx) { "maxGlyphWidthPx exceeds pageSizePx" }
        require(maxGlyphHeightPx <= pageSizePx) { "maxGlyphHeightPx exceeds pageSizePx" }
    }

    companion object {
        fun forGlMaxTextureSize(maxTextureSize: Int): GlyphAtlasLimits {
            val pageSize = minOf(512, maxTextureSize.coerceAtLeast(32))
            return GlyphAtlasLimits(
                pageSizePx = pageSize,
                maxGlyphWidthPx = minOf(256, pageSize),
                maxGlyphHeightPx = minOf(128, pageSize)
            )
        }
    }
}

/** A complete cache key; UTF-16 text is retained exactly, including surrogate pairs. */
data class GlyphAtlasKey(
    val text: String,
    val foregroundArgb: Int,
    val typeface: Typeface?,
    val fontSizePx: Float,
    val cellWidthPx: Float,
    val cellHeightPx: Float,
    val fontAscentPx: Float,
    val cellSpan: Int,
    val textScaleX: Float,
    val bold: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val strikeThrough: Boolean,
    val rasterMode: Int = RASTER_MODE_RGBA
) {
    init {
        require(text.isNotEmpty()) { "GlyphAtlasKey text must not be empty" }
        require(cellSpan >= 1) { "cellSpan must be positive" }
        require(fontSizePx > 0f) { "fontSizePx must be positive" }
        require(cellWidthPx > 0f) { "cellWidthPx must be positive" }
        require(cellHeightPx > 0f) { "cellHeightPx must be positive" }
        require(textScaleX > 0f) { "textScaleX must be positive" }
    }

    companion object {
        const val RASTER_MODE_RGBA = 0
    }
}

/** Atlas coordinates include transparent padding and are valid only for their generation. */
data class GlyphAtlasRegion(
    val pageIndex: Int,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val atlasGeneration: Int,
    /** Screen offset from the terminal cell origin to the atlas quad origin. */
    val drawOffsetX: Float = 0f,
    val drawOffsetY: Float = 0f
) {
    val right: Int
        get() = left + width

    val bottom: Int
        get() = top + height
}

internal data class GlyphAtlasAllocation(
    val region: GlyphAtlasRegion,
    val reset: Boolean
)

/**
 * Deterministic shelf allocator and bounded key cache. It has no GLES or
 * Android bitmap dependency, which keeps capacity and reset behavior JVM-testable.
 */
internal class GlyphAtlasAllocator(
    private val limits: GlyphAtlasLimits
) {
    private val pages = ArrayList<ShelfPage>(limits.maxPages)
    private val entries = LinkedHashMap<GlyphAtlasKey, GlyphAtlasRegion>(
        minOf(limits.maxEntries, 64),
        0.75f,
        true
    )
    private var generation = 1
    private var usedAreaPx = 0
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var resetCount = 0L
    private var largestAllocationPx = 0

    fun find(key: GlyphAtlasKey): GlyphAtlasRegion? {
        val region = entries[key]
        if (region == null) {
            cacheMisses++
        } else {
            cacheHits++
        }
        return region
    }

    fun allocateNew(
        key: GlyphAtlasKey,
        width: Int,
        height: Int,
        drawOffsetX: Float = 0f,
        drawOffsetY: Float = 0f
    ): GlyphAtlasAllocation? {
        require(width > 0) { "width must be positive" }
        require(height > 0) { "height must be positive" }
        if (width > limits.maxGlyphWidthPx || height > limits.maxGlyphHeightPx) return null

        var didReset = false
        if (entries.size >= limits.maxEntries) {
            reset()
            didReset = true
        }

        var allocation = allocateOnExistingPages(width, height)
        if (allocation == null && pages.size < limits.maxPages) {
            pages += ShelfPage(limits.pageSizePx)
            allocation = allocateOnPage(pages.lastIndex, width, height)
        }
        if (allocation == null) {
            reset()
            didReset = true
            pages += ShelfPage(limits.pageSizePx)
            allocation = allocateOnPage(pages.lastIndex, width, height)
        }
        val placement = allocation ?: return null
        val region = GlyphAtlasRegion(
            pageIndex = placement.pageIndex,
            left = placement.left,
            top = placement.top,
            width = width,
            height = height,
            atlasGeneration = generation,
            drawOffsetX = drawOffsetX,
            drawOffsetY = drawOffsetY
        )
        entries[key] = region
        usedAreaPx += width * height
        largestAllocationPx = maxOf(largestAllocationPx, width * height)
        return GlyphAtlasAllocation(region, didReset)
    }

    fun isCurrent(region: GlyphAtlasRegion): Boolean =
        region.atlasGeneration == generation &&
            region.pageIndex in pages.indices

    fun reset() {
        entries.clear()
        pages.clear()
        usedAreaPx = 0
        generation++
        resetCount++
    }

    fun diagnostics(): GlesAtlasDiagnostics = GlesAtlasDiagnostics(
        generation = generation,
        pageCount = pages.size,
        maxPages = limits.maxPages,
        pageSizePx = limits.pageSizePx,
        usedAreaPx = usedAreaPx,
        entryCount = entries.size,
        maxEntries = limits.maxEntries,
        cacheHits = cacheHits,
        cacheMisses = cacheMisses,
        resetCount = resetCount,
        largestAllocationPx = largestAllocationPx
    )

    private fun allocateOnExistingPages(width: Int, height: Int): Placement? {
        for (pageIndex in pages.indices) {
            return allocateOnPage(pageIndex, width, height) ?: continue
        }
        return null
    }

    private fun allocateOnPage(pageIndex: Int, width: Int, height: Int): Placement? {
        val placement = pages[pageIndex].allocate(width, height) ?: return null
        return Placement(pageIndex, placement.left, placement.top)
    }

    private data class Placement(
        val pageIndex: Int,
        val left: Int,
        val top: Int
    )

    private class ShelfPage(private val size: Int) {
        private var cursorX = 0
        private var cursorY = 0
        private var rowHeight = 0

        fun allocate(width: Int, height: Int): Placement? {
            if (width > size || height > size) return null
            if (cursorX + width > size) {
                cursorX = 0
                cursorY += rowHeight
                rowHeight = 0
            }
            if (cursorY + height > size) return null
            val placement = Placement(-1, cursorX, cursorY)
            cursorX += width
            rowHeight = maxOf(rowHeight, height)
            return placement
        }
    }
}

internal data class GlyphRasterGeometry(
    val width: Int,
    val height: Int,
    val drawOffsetX: Float,
    val drawOffsetY: Float,
    val drawOriginX: Float,
    val drawBaselineY: Float
)

internal data class GlyphPaintBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int
        get() = right - left
    val height: Int
        get() = bottom - top
}

/**
 * Computes a bitmap rectangle and its bearing from Android's painted bounds.
 * The atlas quad is positioned at the terminal cell origin, not at the bitmap
 * origin, so skewed glyph overhangs cannot be clipped at cell boundaries.
 */
internal fun glyphRasterGeometry(
    bounds: GlyphPaintBounds,
    measuredWidth: Float,
    cellHeightPx: Float,
    fontAscentPx: Float,
    paddingPx: Int
): GlyphRasterGeometry {
    val fallbackWidth = ceil(measuredWidth.coerceAtLeast(1f)).toInt()
    val boundsWidth = bounds.width
    val boundsHeight = bounds.height
    if (boundsWidth <= 0 || boundsHeight <= 0) {
        val width = fallbackWidth + paddingPx * 2
        val height = ceil(cellHeightPx).toInt() + paddingPx * 2
        val drawOriginX = paddingPx.toFloat()
        val drawBaselineY = paddingPx.toFloat() - fontAscentPx
        return GlyphRasterGeometry(
            width = width,
            height = height,
            drawOffsetX = -drawOriginX,
            drawOffsetY = -paddingPx.toFloat(),
            drawOriginX = drawOriginX,
            drawBaselineY = drawBaselineY
        )
    }

    val width = maxOf(boundsWidth, fallbackWidth) + paddingPx * 2
    val height = boundsHeight + paddingPx * 2
    val drawOriginX = paddingPx - bounds.left.toFloat()
    val drawBaselineY = paddingPx - bounds.top.toFloat()
    return GlyphRasterGeometry(
        width = width,
        height = height,
        drawOffsetX = -drawOriginX,
        drawOffsetY = -fontAscentPx - drawBaselineY,
        drawOriginX = drawOriginX,
        drawBaselineY = drawBaselineY
    )
}

internal data class RasterizedGlyph(
    val bitmap: Bitmap,
    val drawOffsetX: Float,
    val drawOffsetY: Float
)

/** Android Paint/Canvas rasterizer; it is called only by the GL renderer thread. */
internal class AndroidGlyphRasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    fun rasterize(key: GlyphAtlasKey, limits: GlyphAtlasLimits): RasterizedGlyph? {
        paint.reset()
        paint.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        paint.typeface = key.typeface ?: Typeface.MONOSPACE
        paint.textSize = key.fontSizePx
        paint.color = key.foregroundArgb
        paint.textScaleX = key.textScaleX
        paint.isFakeBoldText = key.bold
        paint.textSkewX = if (key.italic) GlesItalicTextSkewX else 0f
        paint.isUnderlineText = key.underline
        paint.isStrikeThruText = key.strikeThrough

        val measuredWidth = paint.measureText(key.text).coerceAtLeast(1f)
        val availableWidth = limits.maxGlyphWidthPx - limits.paddingPx * 2
        if (availableWidth <= 0) return null
        if (measuredWidth > availableWidth) {
            paint.textScaleX *= availableWidth / measuredWidth
        }
        val paintedBounds = Rect()
        paint.getTextBounds(key.text, 0, key.text.length, paintedBounds)
        val geometry = glyphRasterGeometry(
            bounds = GlyphPaintBounds(
                left = paintedBounds.left,
                top = paintedBounds.top,
                right = paintedBounds.right,
                bottom = paintedBounds.bottom
            ),
            measuredWidth = paint.measureText(key.text),
            cellHeightPx = key.cellHeightPx,
            fontAscentPx = key.fontAscentPx,
            paddingPx = limits.paddingPx
        )
        if (geometry.width > limits.maxGlyphWidthPx || geometry.height > limits.maxGlyphHeightPx) {
            return null
        }

        val bitmap = Bitmap.createBitmap(geometry.width, geometry.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val chars = key.text.toCharArray()
        canvas.drawTextRun(
            chars,
            0,
            chars.size,
            0,
            chars.size,
            geometry.drawOriginX,
            geometry.drawBaselineY,
            false,
            paint
        )
        return RasterizedGlyph(bitmap, geometry.drawOffsetX, geometry.drawOffsetY)
    }
}

/** GLES-backed atlas that uploads only newly rasterized bounded regions. */
internal class GlesGlyphAtlas(
    private val limits: GlyphAtlasLimits
) {
    private val allocator = GlyphAtlasAllocator(limits)
    private val rasterizer = AndroidGlyphRasterizer()
    private var textureIds = IntArray(0)

    val pageSizePx: Int
        get() = limits.pageSizePx

    val maxPages: Int
        get() = limits.maxPages

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    fun resolve(
        key: GlyphAtlasKey,
        beforeReset: () -> Unit = {}
    ): GlyphAtlasRegion? {
        val existing = allocator.find(key)
        if (existing != null) return existing

        val rasterized = rasterizer.rasterize(key, limits) ?: return null
        val allocation = allocator.allocateNew(
            key = key,
            width = rasterized.bitmap.width,
            height = rasterized.bitmap.height,
            drawOffsetX = rasterized.drawOffsetX,
            drawOffsetY = rasterized.drawOffsetY
        )
        if (allocation == null) {
            if (!rasterized.bitmap.isRecycled) rasterized.bitmap.recycle()
            return null
        }
        try {
            if (allocation.reset) {
                try {
                    beforeReset()
                } finally {
                    deleteTextures()
                }
            }
            val texture = ensureTexture(allocation.region.pageIndex)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLUtils.texSubImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                allocation.region.left,
                allocation.region.top,
                rasterized.bitmap
            )
            checkGlError("atlas-upload")
        } catch (error: RuntimeException) {
            reset(beforeReset)
            throw GlesResourceException("atlas texture upload failed", error)
        } finally {
            if (!rasterized.bitmap.isRecycled) rasterized.bitmap.recycle()
        }
        return allocation.region
    }

    fun diagnostics(): GlesAtlasDiagnostics = allocator.diagnostics()

    fun textureId(pageIndex: Int): Int = textureIds.getOrElse(pageIndex) { 0 }

    fun reset(beforeReset: () -> Unit = {}) {
        try {
            try {
                beforeReset()
            } finally {
                deleteTextures()
            }
        } finally {
            allocator.reset()
        }
    }

    fun release() {
        try {
            deleteTextures()
        } finally {
            allocator.reset()
        }
    }

    private fun ensureTexture(pageIndex: Int): Int {
        if (pageIndex < textureIds.size && textureIds[pageIndex] != 0) {
            return textureIds[pageIndex]
        }
        val next = IntArray(pageIndex + 1)
        textureIds.copyInto(next)
        textureIds = next
        val texture = IntArray(1)
        GLES30.glGenTextures(1, texture, 0)
        val id = texture[0]
        if (id != 0) textureIds[pageIndex] = id
        checkGlError("atlas-generate")
        if (id == 0) throw GlesResourceException("atlas texture: glGenTextures returned 0")
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            limits.pageSizePx,
            limits.pageSizePx,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        checkGlError("atlas-allocate")
        return id
    }

    private fun deleteTextures() {
        if (textureIds.isEmpty()) return
        try {
            GLES30.glDeleteTextures(textureIds.size, textureIds, 0)
            checkGlError("atlas-delete")
        } finally {
            textureIds = IntArray(0)
        }
    }

    private fun checkGlError(stage: String) {
        repeat(8) {
            val error = GLES30.glGetError()
            if (error == GLES30.GL_NO_ERROR) return
            throw GlesResourceException("$stage: 0x${error.toString(16)}")
        }
    }

}

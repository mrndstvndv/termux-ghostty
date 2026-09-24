package com.termux.terminal.compose

import android.graphics.Bitmap

/** How a [TerminalWallpaper] is scaled to the terminal viewport. */
enum class WallpaperScaling {
    /** Fill the viewport, cropping the image's overflow. */
    CENTER_CROP,

    /** Fit the whole image inside the viewport, letterboxing the remainder. */
    FIT_CENTER,

    /** Stretch the image to exactly fill the viewport. */
    FIT_XY,

    /** Draw the image at its intrinsic size, centered and cropped if oversized. */
    CENTER
}

/**
 * Consumer-controlled wallpaper policy for [TerminalCanvas].
 *
 * The wallpaper is drawn behind default-background terminal cells only. Cells
 * with an explicit background color, selections, and block cursors stay opaque,
 * so text contrast is preserved. A null [wallpaper] leaves rendering unchanged.
 */
data class TerminalWallpaperConfig(
    val wallpaper: TerminalWallpaper? = null,
    /**
     * Opacity of the default terminal background color over the wallpaper.
     * `1f` hides the wallpaper; `0f` shows it fully and removes the default
     * background scrim. Ignored when [wallpaper] is null.
     */
    val backgroundOpacity: Float = DefaultBackgroundOpacity,
    val scaling: WallpaperScaling = WallpaperScaling.CENTER_CROP
) {
    init {
        require(backgroundOpacity in 0f..1f) { "backgroundOpacity must be within 0f..1f" }
    }

    companion object {
        /** Subtle but visible wallpaper scrim, chosen to keep text legible by default. */
        const val DefaultBackgroundOpacity = 0.7f
    }
}

/**
 * Immutable, thread-safe wallpaper pixels owned by the renderer.
 *
 * The holder copies its source pixels, so the consumer keeps ownership of any
 * [Bitmap] it passes to [fromBitmap]. Instances are shared across threads: the
 * backing array is never mutated after construction. [id] is a content
 * generation: publish a new instance with a new [id] whenever the pixels change
 * so the renderer can discard a stale GPU texture.
 */
class TerminalWallpaper private constructor(
    val id: Long,
    val width: Int,
    val height: Int,
    internal val argb: IntArray
) {
    init {
        val expectedPixels = requireRepresentablePixelCount(width, height)
        require(argb.size == expectedPixels) { "pixel count must equal width * height" }
    }

    /** Intrinsic width divided by intrinsic height. */
    val aspectRatio: Float
        get() = width.toFloat() / height.toFloat()

    companion object {
        /**
         * Copies ARGB_8888 pixels into an immutable holder.
         *
         * [pixels] is row-major with a top-left origin. The array is copied, so
         * later mutation by the caller cannot alter the wallpaper.
         */
        fun ofArgb(id: Long, width: Int, height: Int, pixels: IntArray): TerminalWallpaper {
            val expectedPixels = requireRepresentablePixelCount(width, height)
            require(pixels.size == expectedPixels) { "pixel count must equal width * height" }
            return TerminalWallpaper(id, width, height, pixels.copyOf())
        }

        /**
         * Snapshots a decoded bitmap into an immutable holder.
         *
         * The bitmap is copied through [Bitmap.getPixels], which also normalizes
         * any source config to ARGB_8888. The caller retains ownership of
         * [bitmap] and may recycle it after this call returns.
         */
        fun fromBitmap(id: Long, bitmap: Bitmap): TerminalWallpaper {
            require(!bitmap.isRecycled) { "wallpaper bitmap is recycled" }
            val width = bitmap.width
            val height = bitmap.height
            require(width > 0 && height > 0) { "wallpaper bitmap must be non-empty" }
            val pixels = IntArray(requireRepresentablePixelCount(width, height))
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            return TerminalWallpaper(id, width, height, pixels)
        }
    }
}

/**
 * Validates that [width] and [height] describe a positive, representable pixel
 * count and returns it.
 *
 * Two positive `Int`s can overflow when multiplied, so the product is computed
 * in `Long` and rejected before any pixel array is allocated.
 */
internal fun requireRepresentablePixelCount(width: Int, height: Int): Int {
    require(width > 0 && height > 0) { "wallpaper dimensions must be positive: ${width}x$height" }
    val pixelCount = width.toLong() * height.toLong()
    require(pixelCount <= Int.MAX_VALUE) {
        "wallpaper pixel count is not representable: ${width}x$height"
    }
    return pixelCount.toInt()
}

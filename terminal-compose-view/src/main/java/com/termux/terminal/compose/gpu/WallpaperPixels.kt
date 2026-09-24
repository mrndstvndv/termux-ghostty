package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.WallpaperScaling
import com.termux.terminal.compose.requireRepresentablePixelCount

/**
 * A wallpaper draw in viewport pixels plus the source UVs to sample.
 *
 * [u0]/[v0]/[u1]/[v1] are normalized in image space with a top-left origin.
 * They are a subrange of `0f..1f` when a scaling mode crops the image
 * (e.g. CENTER_CROP), and exactly `0f..1f` otherwise.
 */
internal data class WallpaperRectPlan(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val u0: Float,
    val v0: Float,
    val u1: Float,
    val v1: Float
)

/**
 * Computes the on-screen rect and source UVs for a wallpaper.
 *
 * Pure geometry with no GL or Android dependency, so the crop/fit/letterbox
 * behavior is covered by host unit tests. Returns null for degenerate geometry.
 */
internal fun planWallpaperRect(
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    imageWidth: Int,
    imageHeight: Int,
    scaling: WallpaperScaling
): WallpaperRectPlan? {
    val degenerateViewport = viewportWidthPx <= 0 || viewportHeightPx <= 0
    val degenerateImage = imageWidth <= 0 || imageHeight <= 0
    if (degenerateViewport || degenerateImage) return null
    val viewportWidth = viewportWidthPx.toFloat()
    val viewportHeight = viewportHeightPx.toFloat()
    val imageAspectWidth = imageWidth.toFloat()
    val imageAspectHeight = imageHeight.toFloat()
    val (scaledWidth, scaledHeight) = when (scaling) {
        WallpaperScaling.FIT_XY -> viewportWidth to viewportHeight
        WallpaperScaling.FIT_CENTER -> {
            val scale = minOf(viewportWidth / imageAspectWidth, viewportHeight / imageAspectHeight)
            imageAspectWidth * scale to imageAspectHeight * scale
        }
        WallpaperScaling.CENTER_CROP -> {
            val scale = maxOf(viewportWidth / imageAspectWidth, viewportHeight / imageAspectHeight)
            imageAspectWidth * scale to imageAspectHeight * scale
        }
        WallpaperScaling.CENTER -> imageAspectWidth to imageAspectHeight
    }
    val left = (viewportWidth - scaledWidth) / 2f
    val top = (viewportHeight - scaledHeight) / 2f
    val visibleLeft = maxOf(0f, left)
    val visibleTop = maxOf(0f, top)
    val visibleRight = minOf(viewportWidth, left + scaledWidth)
    val visibleBottom = minOf(viewportHeight, top + scaledHeight)
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
    return WallpaperRectPlan(
        left = visibleLeft,
        top = visibleTop,
        right = visibleRight,
        bottom = visibleBottom,
        u0 = (visibleLeft - left) / scaledWidth,
        v0 = (visibleTop - top) / scaledHeight,
        u1 = (visibleRight - left) / scaledWidth,
        v1 = (visibleBottom - top) / scaledHeight
    )
}

/** Immutable ARGB_8888 pixels produced by [downscaleArgb]. */
internal class ArgbPixels(
    val width: Int,
    val height: Int,
    val pixels: IntArray
)

/**
 * Box-filters [pixels] so the longest side fits within [maxDimension].
 *
 * Returns the original pixels untouched when no downscale is required. Keeps
 * oversized wallpapers inside `GL_MAX_TEXTURE_SIZE` without dropping a frame:
 * the renderer runs this once per image on the GL thread.
 */
internal fun downscaleArgb(
    pixels: IntArray,
    width: Int,
    height: Int,
    maxDimension: Int
): ArgbPixels {
    require(pixels.size == requireRepresentablePixelCount(width, height)) {
        "pixel count must equal width * height"
    }
    val longest = maxOf(width, height)
    if (maxDimension <= 0 || longest <= maxDimension) return ArgbPixels(width, height, pixels)
    val scale = maxDimension.toFloat() / longest
    val targetWidth = (width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (height * scale).toInt().coerceAtLeast(1)
    val target = IntArray(targetWidth * targetHeight)
    for (targetY in 0 until targetHeight) {
        val sourceTop = (targetY.toLong() * height / targetHeight).toInt()
        val sourceBottom = ((targetY + 1).toLong() * height / targetHeight)
            .toInt()
            .coerceAtLeast(sourceTop + 1)
        for (targetX in 0 until targetWidth) {
            val sourceLeft = (targetX.toLong() * width / targetWidth).toInt()
            val sourceRight = ((targetX + 1).toLong() * width / targetWidth)
                .toInt()
                .coerceAtLeast(sourceLeft + 1)
            target[targetY * targetWidth + targetX] =
                averageBlock(pixels, width, sourceLeft, sourceTop, sourceRight, sourceBottom)
        }
    }
    return ArgbPixels(targetWidth, targetHeight, target)
}

private fun averageBlock(
    pixels: IntArray,
    width: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int
): Int {
    var alpha = 0L
    var premultipliedRed = 0L
    var premultipliedGreen = 0L
    var premultipliedBlue = 0L
    var count = 0
    for (y in top until bottom) {
        val rowStart = y * width
        for (x in left until right) {
            val argb = pixels[rowStart + x]
            val pixelAlpha = (argb ushr 24) and 0xFF
            alpha += pixelAlpha
            premultipliedRed += ((argb ushr 16) and 0xFF) * pixelAlpha
            premultipliedGreen += ((argb ushr 8) and 0xFF) * pixelAlpha
            premultipliedBlue += (argb and 0xFF) * pixelAlpha
            count++
        }
    }
    if (count == 0) return 0
    val red = if (alpha == 0L) 0 else (premultipliedRed / alpha).toInt()
    val green = if (alpha == 0L) 0 else (premultipliedGreen / alpha).toInt()
    val blue = if (alpha == 0L) 0 else (premultipliedBlue / alpha).toInt()
    return (((alpha / count).toInt() and 0xFF) shl 24) or
        ((red and 0xFF) shl 16) or
        ((green and 0xFF) shl 8) or
        (blue and 0xFF)
}

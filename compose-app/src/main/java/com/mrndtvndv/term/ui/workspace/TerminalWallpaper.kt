package com.mrndtvndv.term.ui.workspace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.termux.terminal.compose.TerminalWallpaper
import com.termux.terminal.compose.TerminalWallpaperConfig
import com.termux.terminal.compose.WallpaperScaling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** Upper bound for the decoded wallpaper's longest side, to bound peak memory. */
internal const val WallpaperDecodeMaxDimension = 2048

internal fun WallpaperScaling.label(): String = when (this) {
    WallpaperScaling.CENTER_CROP -> "Crop"
    WallpaperScaling.FIT_CENTER -> "Fit"
    WallpaperScaling.FIT_XY -> "Stretch"
    WallpaperScaling.CENTER -> "Center"
}

internal fun wallpaperScalingFromPref(value: String?): WallpaperScaling =
    WallpaperScaling.entries.firstOrNull { it.name == value } ?: WallpaperScaling.CENTER_CROP

/**
 * Loads the persisted wallpaper off the main thread and assembles the current
 * rendering policy. The decoded [TerminalWallpaper] is cached until the URI,
 * content id, or enablement changes. A [predecoded] wallpaper from a successful
 * pick is reused instead of decoded a second time.
 */
@Composable
internal fun rememberTerminalWallpaperConfig(
    uri: String?,
    id: Long,
    enabled: Boolean,
    backgroundOpacity: Float,
    scaling: WallpaperScaling,
    predecoded: TerminalWallpaper? = null
): TerminalWallpaperConfig {
    val wallpaper = rememberTerminalWallpaper(uri, id, enabled, predecoded)
    return remember(wallpaper, backgroundOpacity, scaling) {
        TerminalWallpaperConfig(
            wallpaper = wallpaper,
            backgroundOpacity = backgroundOpacity,
            scaling = scaling
        )
    }
}

@Composable
private fun rememberTerminalWallpaper(
    uri: String?,
    id: Long,
    enabled: Boolean,
    predecoded: TerminalWallpaper?
): TerminalWallpaper? {
    val context = LocalContext.current
    val reusable = reusableWallpaper(predecoded, id)
    var wallpaper by remember { mutableStateOf<TerminalWallpaper?>(null) }
    LaunchedEffect(uri, id, enabled, reusable) {
        wallpaper = when {
            !enabled || uri.isNullOrEmpty() -> null
            reusable != null -> reusable
            else -> withContext(Dispatchers.IO) { decodeTerminalWallpaper(context, uri, id) }
        }
    }
    return wallpaper
}

/** Reuses an already-decoded wallpaper only while it belongs to the current content generation. */
internal fun reusableWallpaper(predecoded: TerminalWallpaper?, id: Long): TerminalWallpaper? =
    predecoded?.takeIf { it.id == id }

/**
 * Keeps the freshly-decoded wallpaper in the in-memory cache only while wallpapering
 * is enabled. Disabling drops the decoded bitmap so its memory can be reclaimed; the
 * saved URI/name/id and their persisted read grant are untouched, so re-enabling
 * reloads from the saved URI instead of the dropped cache.
 */
internal fun retainedPredecodedWallpaper(
    wallpaper: TerminalWallpaper?,
    enabled: Boolean,
): TerminalWallpaper? = wallpaper.takeIf { enabled }

@Suppress("TooGenericExceptionCaught")
internal fun decodeTerminalWallpaper(context: Context, uri: String, id: Long): TerminalWallpaper? =
    try {
        decodeWallpaper(context, uri, id)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Log.w("TerminalWallpaper", "Failed to decode wallpaper: $uri", error)
        null
    }

private fun decodeWallpaper(context: Context, uri: String, id: Long): TerminalWallpaper? {
    val parsed = Uri.parse(uri)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(parsed)?.use {
        BitmapFactory.decodeStream(it, null, bounds)
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateSampleSize(
            width = bounds.outWidth,
            height = bounds.outHeight,
            maxDimension = WallpaperDecodeMaxDimension
        )
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val bitmap = context.contentResolver.openInputStream(parsed)?.use {
        BitmapFactory.decodeStream(it, null, options)
    } ?: return null
    return try {
        TerminalWallpaper.fromBitmap(id, bitmap)
    } finally {
        bitmap.recycle()
    }
}

/**
 * Smallest power-of-two decode sample size whose integer-scaled dimensions keep
 * both sides at or below [maxDimension], bounding peak decode memory.
 */
internal fun calculateSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    if (maxDimension <= 0) return 1
    var sampleSize = 1
    while (width / sampleSize > maxDimension || height / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}

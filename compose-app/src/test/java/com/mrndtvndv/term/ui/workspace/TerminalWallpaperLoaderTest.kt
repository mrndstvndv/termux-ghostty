package com.mrndtvndv.term.ui.workspace

import com.termux.terminal.compose.TerminalWallpaper
import com.termux.terminal.compose.WallpaperScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TerminalWallpaperLoaderTest {
    @Test
    fun `sample size bounds the longest side to the target`() {
        assertEquals(2, calculateSampleSize(width = 4000, height = 3000, maxDimension = 2048))
        assertEquals(4, calculateSampleSize(width = 8000, height = 6000, maxDimension = 2048))
        assertEquals(8, calculateSampleSize(width = 16000, height = 12000, maxDimension = 2048))
    }

    @Test
    fun `sample size stays at one when already within the target`() {
        assertEquals(1, calculateSampleSize(width = 2048, height = 1536, maxDimension = 2048))
        assertEquals(1, calculateSampleSize(width = 1920, height = 1080, maxDimension = 2048))
    }

    @Test
    fun `sample size rounds up to the smallest sufficient power of two`() {
        // One pixel over the target already forces the next sample size.
        assertEquals(2, calculateSampleSize(width = 2049, height = 1, maxDimension = 2048))
    }

    @Test
    fun `sample size uses the dominant axis`() {
        assertEquals(4, calculateSampleSize(width = 5000, height = 1000, maxDimension = 2048))
        assertEquals(4, calculateSampleSize(width = 1000, height = 5000, maxDimension = 2048))
    }

    @Test
    fun `sample size is one for a non-positive target`() {
        assertEquals(1, calculateSampleSize(width = 8000, height = 6000, maxDimension = 0))
    }

    @Test
    fun `sample size does not overflow for extreme image bounds`() {
        assertEquals(1 shl 30, calculateSampleSize(width = Int.MAX_VALUE, height = 1, maxDimension = 1))
    }

    @Test
    fun `scaling pref round-trips and falls back safely`() {
        assertEquals(
            WallpaperScaling.FIT_CENTER,
            wallpaperScalingFromPref(WallpaperScaling.FIT_CENTER.name)
        )
        assertEquals(
            WallpaperScaling.CENTER_CROP,
            wallpaperScalingFromPref(null)
        )
        assertEquals(
            WallpaperScaling.CENTER_CROP,
            wallpaperScalingFromPref("not-a-scaling-mode")
        )
    }

    @Test
    fun `predecoded wallpaper is reused only for its own content id`() {
        val wallpaper = TerminalWallpaper.ofArgb(id = 7L, width = 1, height = 1, pixels = intArrayOf(0))

        assertSame(wallpaper, reusableWallpaper(wallpaper, 7L))
        assertNull(reusableWallpaper(wallpaper, 8L))
        assertNull(reusableWallpaper(null, 7L))
    }

    @Test
    fun `decoded wallpaper cache is retained only while enabled`() {
        val wallpaper = TerminalWallpaper.ofArgb(id = 3L, width = 1, height = 1, pixels = intArrayOf(0))

        assertSame(wallpaper, retainedPredecodedWallpaper(wallpaper, enabled = true))
        assertNull(retainedPredecodedWallpaper(wallpaper, enabled = false))
        assertNull(retainedPredecodedWallpaper(null, enabled = true))
    }

    @Test
    fun `every scaling mode has a label`() {
        for (scaling in WallpaperScaling.entries) {
            assertEquals(true, scaling.label().isNotBlank())
        }
    }
}

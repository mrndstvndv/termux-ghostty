package com.termux.terminal.compose

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalWallpaperTest {
    @Test
    fun `ofArgb copies its source so caller mutation cannot leak`() {
        val source = intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt())

        val wallpaper = TerminalWallpaper.ofArgb(id = 1L, width = 2, height = 1, pixels = source)
        source[0] = 0

        assertEquals(2, wallpaper.width)
        assertEquals(1, wallpaper.height)
        assertArrayEquals(intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()), wallpaper.argb)
    }

    @Test
    fun `aspect ratio reflects intrinsic dimensions`() {
        val wallpaper = TerminalWallpaper.ofArgb(2L, width = 16, height = 8, pixels = IntArray(128))

        assertEquals(2f, wallpaper.aspectRatio, 0f)
    }

    @Test
    fun `pixel count must match dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaper.ofArgb(3L, width = 2, height = 2, pixels = IntArray(3))
        }
    }

    @Test
    fun `ofArgb rejects overflowing dimensions without allocating`() {
        // 65536 * 65536 wraps to 0 in Int arithmetic, so an empty array must not be accepted.
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaper.ofArgb(4L, width = 65536, height = 65536, pixels = IntArray(0))
        }
    }

    @Test
    fun `ofArgb rejects pixel counts above the Int range`() {
        // 65536 * 65537 wraps to 65536 in Int arithmetic, matching the supplied array size.
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaper.ofArgb(5L, width = 65536, height = 65537, pixels = IntArray(65536))
        }
    }

    @Test
    fun `ofArgb rejects non-positive dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaper.ofArgb(6L, width = 0, height = 4, pixels = IntArray(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaper.ofArgb(7L, width = 4, height = -1, pixels = IntArray(0))
        }
    }

    @Test
    fun `config default is inert and visible opacity override is accepted`() {
        val defaults = TerminalWallpaperConfig()

        assertNull(defaults.wallpaper)
        assertEquals(WallpaperScaling.CENTER_CROP, defaults.scaling)
        assertTrue(defaults.backgroundOpacity in 0f..1f)

        val pinned = TerminalWallpaperConfig(backgroundOpacity = 0.25f)
        assertEquals(0.25f, pinned.backgroundOpacity, 0f)
    }

    @Test
    fun `config rejects opacity outside unit range`() {
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaperConfig(backgroundOpacity = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TerminalWallpaperConfig(backgroundOpacity = Float.NaN)
        }
    }
}

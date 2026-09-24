package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.WallpaperScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class WallpaperGeometryTest {
    @Test
    fun `fit xy fills the viewport with the full image`() {
        val plan = planWallpaperRect(100, 100, 200, 50, WallpaperScaling.FIT_XY)!!

        assertEquals(0f, plan.left, Tolerance)
        assertEquals(0f, plan.top, Tolerance)
        assertEquals(100f, plan.right, Tolerance)
        assertEquals(100f, plan.bottom, Tolerance)
        assertEquals(0f, plan.u0, Tolerance)
        assertEquals(0f, plan.v0, Tolerance)
        assertEquals(1f, plan.u1, Tolerance)
        assertEquals(1f, plan.v1, Tolerance)
    }

    @Test
    fun `fit center letterboxes without cropping source`() {
        val plan = planWallpaperRect(100, 100, 200, 100, WallpaperScaling.FIT_CENTER)!!

        assertEquals(0f, plan.left, Tolerance)
        assertEquals(25f, plan.top, Tolerance)
        assertEquals(100f, plan.right, Tolerance)
        assertEquals(75f, plan.bottom, Tolerance)
        assertEquals(0f, plan.u0, Tolerance)
        assertEquals(1f, plan.u1, Tolerance)
    }

    @Test
    fun `center crop fills viewport and crops the overflowing axis`() {
        val plan = planWallpaperRect(100, 100, 200, 100, WallpaperScaling.CENTER_CROP)!!

        assertEquals(0f, plan.left, Tolerance)
        assertEquals(0f, plan.top, Tolerance)
        assertEquals(100f, plan.right, Tolerance)
        assertEquals(100f, plan.bottom, Tolerance)
        assertEquals(0.25f, plan.u0, Tolerance)
        assertEquals(0.75f, plan.u1, Tolerance)
        assertEquals(0f, plan.v0, Tolerance)
        assertEquals(1f, plan.v1, Tolerance)
    }

    @Test
    fun `center keeps intrinsic size and letterboxes a small image`() {
        val plan = planWallpaperRect(100, 100, 50, 50, WallpaperScaling.CENTER)!!

        assertEquals(25f, plan.left, Tolerance)
        assertEquals(25f, plan.top, Tolerance)
        assertEquals(75f, plan.right, Tolerance)
        assertEquals(75f, plan.bottom, Tolerance)
        assertEquals(0f, plan.u0, Tolerance)
        assertEquals(1f, plan.u1, Tolerance)
    }

    @Test
    fun `center crops an oversized image symmetrically`() {
        val plan = planWallpaperRect(100, 100, 200, 200, WallpaperScaling.CENTER)!!

        assertEquals(0f, plan.left, Tolerance)
        assertEquals(0f, plan.top, Tolerance)
        assertEquals(100f, plan.right, Tolerance)
        assertEquals(100f, plan.bottom, Tolerance)
        assertEquals(0.25f, plan.u0, Tolerance)
        assertEquals(0.75f, plan.u1, Tolerance)
        assertEquals(0.25f, plan.v0, Tolerance)
        assertEquals(0.75f, plan.v1, Tolerance)
    }

    @Test
    fun `degenerate viewport or image yields no plan`() {
        assertNull(planWallpaperRect(0, 100, 10, 10, WallpaperScaling.CENTER_CROP))
        assertNull(planWallpaperRect(100, 0, 10, 10, WallpaperScaling.CENTER_CROP))
        assertNull(planWallpaperRect(100, 100, 0, 10, WallpaperScaling.CENTER_CROP))
        assertNull(planWallpaperRect(100, 100, 10, 0, WallpaperScaling.CENTER_CROP))
    }

    @Test
    fun `downscale returns original pixels when within the limit`() {
        val pixels = IntArray(8) { 0xFF202020.toInt() }

        val result = downscaleArgb(pixels, width = 4, height = 2, maxDimension = 4)

        assertEquals(4, result.width)
        assertEquals(2, result.height)
        assert(result.pixels === pixels)
    }

    @Test
    fun `downscale box filters oversized pixels`() {
        val pixels = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(),
            0xFFFF0000.toInt(), 0xFFFF0000.toInt()
        )

        val result = downscaleArgb(pixels, width = 2, height = 2, maxDimension = 1)

        assertEquals(1, result.width)
        assertEquals(1, result.height)
        assertEquals(0xFF7F0000.toInt(), result.pixels[0])
    }

    @Test
    fun `downscale ignores hidden colors in fully transparent pixels`() {
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0x0000FF00)

        val result = downscaleArgb(pixels, width = 2, height = 1, maxDimension = 1)

        assertEquals(0x7FFF0000, result.pixels[0])
    }

    @Test
    fun `downscale rejects overflowing source pixel counts before allocating`() {
        assertThrows(IllegalArgumentException::class.java) {
            downscaleArgb(pixels = IntArray(0), width = 65536, height = 65536, maxDimension = 1)
        }
    }

    private companion object {
        const val Tolerance = 0.0001f
    }
}

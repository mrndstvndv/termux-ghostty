package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.TerminalWallpaper
import com.termux.terminal.compose.TerminalWallpaperConfig
import com.termux.terminal.compose.WallpaperScaling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesTerminalSnapshotTest {
    @Test
    fun snapshotCarriesCompleteFrameAndVisualConfig() {
        val visual = GlesTerminalVisualConfig(fontSizePx = 18f)
        val snapshot = testSnapshot(21L).copy(visual = visual)

        assertEquals(18f, snapshot.visual.fontSizePx)
        assertEquals(21L, snapshot.contentRevision)
        assertEquals(21L, snapshot.presentationRevision)
        assertTrue(snapshot.frame.rows.size == snapshot.frame.rowsVisible)
    }

    @Test
    fun presentationRevisionCanAdvanceWithoutChangingTerminalSequence() {
        val snapshot = testSnapshot(22L).copy(visualOffsetPx = 7.5f)

        val next = snapshot.withPresentationRevision(23L)

        assertEquals(22L, next.frame.sequence)
        assertEquals(23L, next.presentationRevision)
        assertEquals(7.5f, next.visualOffsetPx, 0f)
    }

    @Test
    fun visualConfigCarriesWallpaperWithoutTouchingRows() {
        val wallpaper = TerminalWallpaper.ofArgb(5L, width = 2, height = 2, pixels = IntArray(4))
        val visual = GlesTerminalVisualConfig(
            fontSizePx = 18f,
            wallpaper = TerminalWallpaperConfig(
                wallpaper = wallpaper,
                backgroundOpacity = 0.5f,
                scaling = WallpaperScaling.FIT_CENTER
            )
        )

        val snapshot = testSnapshot(24L).copy(visual = visual)

        assertEquals(wallpaper, snapshot.visual.wallpaper.wallpaper)
        assertEquals(0.5f, snapshot.visual.wallpaper.backgroundOpacity, 0f)
        assertEquals(WallpaperScaling.FIT_CENTER, snapshot.visual.wallpaper.scaling)
        assertEquals(24L, snapshot.frame.sequence)
    }
}

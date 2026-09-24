package com.termux.terminal.compose.gpu

import com.termux.terminal.compose.TerminalWallpaper
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GlesWallpaperTextureTest {
    @Test
    fun `same wallpaper id reuses the uploaded texture`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)
        val wallpaper = wallpaper(id = 1L)

        val first = texture.textureId(wallpaper, MaxTextureSize)
        val second = texture.textureId(wallpaper, MaxTextureSize)

        assertEquals(first, second)
        assertEquals(listOf(1), api.created)
        assertTrue(api.deleted.isEmpty())
    }

    @Test
    fun `uploaded rgba bytes are premultiplied`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)
        val wallpaper = TerminalWallpaper.ofArgb(
            id = 1L,
            width = 1,
            height = 1,
            pixels = intArrayOf(0x80804020.toInt())
        )

        texture.textureId(wallpaper, MaxTextureSize)

        assertEquals(listOf(64, 32, 16, 128), api.uploads.single().map { it.toInt() and 0xFF })
    }

    @Test
    fun `a new wallpaper id replaces the cached texture`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)

        val first = texture.textureId(wallpaper(id = 1L), MaxTextureSize)
        val second = texture.textureId(wallpaper(id = 2L), MaxTextureSize)

        assertNotEquals(first, second)
        assertEquals(listOf(1), api.deleted)
    }

    @Test
    fun `releaseIfAbsent frees the cached texture when the wallpaper is cleared`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)
        texture.textureId(wallpaper(id = 1L), MaxTextureSize)

        assertNull(texture.releaseIfAbsent(null))
        assertEquals(listOf(1), api.deleted)

        // A later re-enable must re-upload rather than reuse the freed texture id.
        assertEquals(2, texture.textureId(wallpaper(id = 1L), MaxTextureSize) ?: -1)
    }

    @Test
    fun `releaseIfAbsent keeps a present wallpaper`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)
        val wallpaper = wallpaper(id = 7L)
        val uploaded = texture.textureId(wallpaper, MaxTextureSize)

        assertSame(wallpaper, texture.releaseIfAbsent(wallpaper))
        assertTrue(api.deleted.isEmpty())
        assertEquals(uploaded, texture.textureId(wallpaper, MaxTextureSize))
    }

    @Test
    fun `release is idempotent`() {
        val api = RecordingTextureApi()
        val texture = GlesWallpaperTexture(api)
        texture.textureId(wallpaper(id = 1L), MaxTextureSize)

        texture.release()
        texture.release()

        assertEquals(listOf(1), api.deleted)
    }

    @Test
    fun `a failed upload is not cached`() {
        val api = RecordingTextureApi()
        api.failNextUpload = true
        val texture = GlesWallpaperTexture(api)

        assertNull(texture.textureId(wallpaper(id = 1L), MaxTextureSize))
        assertTrue(api.deleted.isEmpty())

        assertEquals(1, texture.textureId(wallpaper(id = 1L), MaxTextureSize) ?: -1)
    }

    private fun wallpaper(id: Long): TerminalWallpaper =
        TerminalWallpaper.ofArgb(id, width = 2, height = 2, pixels = IntArray(4))

    private class RecordingTextureApi : WallpaperTextureApi {
        val created = mutableListOf<Int>()
        val deleted = mutableListOf<Int>()
        val uploads = mutableListOf<ByteArray>()
        var failNextUpload = false
        private var nextTextureId = 1

        override fun createTexture(width: Int, height: Int, rgba: ByteBuffer): Int {
            if (failNextUpload) {
                failNextUpload = false
                return 0
            }
            val upload = ByteArray(rgba.remaining())
            rgba.duplicate().get(upload)
            uploads += upload
            return (nextTextureId++).also { created += it }
        }

        override fun deleteTexture(textureId: Int) {
            deleted += textureId
        }
    }

    private companion object {
        const val MaxTextureSize = 4096
    }
}

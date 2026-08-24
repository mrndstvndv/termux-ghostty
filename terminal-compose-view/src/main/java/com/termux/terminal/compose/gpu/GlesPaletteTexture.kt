package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import com.termux.terminal.compose.TerminalPalette
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val PaletteEntryCount = 259
private const val PaletteTextureUnit = GLES30.GL_TEXTURE1

/** Uploads terminal colors once so style resolution can happen in the vertex shader. */
internal class GlesPaletteTexture {
    private val pixels = ByteBuffer
        .allocateDirect(PaletteEntryCount * 4)
        .order(ByteOrder.nativeOrder())
    private var textureId = 0
    private var lastPalette: TerminalPalette? = null
    private var lastVersion = Int.MIN_VALUE
    private var released = false

    fun bind(palette: TerminalPalette) {
        check(!released) { "palette texture is released" }
        ensureTexture()
        GLES30.glActiveTexture(PaletteTextureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        if (lastPalette === palette && lastVersion == palette.version) return
        pixels.clear()
        repeat(PaletteEntryCount) { index ->
            val argb = palette.color(index)
            pixels.put(((argb ushr 16) and 0xFF).toByte())
            pixels.put(((argb ushr 8) and 0xFF).toByte())
            pixels.put((argb and 0xFF).toByte())
            pixels.put(((argb ushr 24) and 0xFF).toByte())
        }
        pixels.flip()
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            PaletteEntryCount,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        lastPalette = palette
        lastVersion = palette.version
    }

    fun release() {
        if (released) return
        released = true
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        lastPalette = null
        lastVersion = Int.MIN_VALUE
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        if (ids[0] == 0) throw GlesResourceException("palette-texture: glGenTextures returned 0")
        textureId = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            GLES30.GL_NEAREST
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_S,
            GLES30.GL_CLAMP_TO_EDGE
        )
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_WRAP_T,
            GLES30.GL_CLAMP_TO_EDGE
        )
    }
}

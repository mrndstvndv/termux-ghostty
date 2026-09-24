package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import com.termux.terminal.compose.TerminalWallpaper
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class GlesWallpaperResources(
    val program: GlesWallpaperProgram?,
    val texture: GlesWallpaperTexture
) {
    fun release() {
        texture.release()
        program?.release()
    }
}

/** GL texture operations used by [GlesWallpaperTexture]; injectable so the cache is host-testable. */
internal interface WallpaperTextureApi {
    /**
     * Uploads premultiplied RGBA bytes and returns the new
     * texture id, or 0 when the upload fails.
     */
    fun createTexture(width: Int, height: Int, rgba: ByteBuffer): Int

    fun deleteTexture(textureId: Int)
}

/**
 * GL-thread cache for one wallpaper texture.
 *
 * The texture is rebuilt only when the published wallpaper id changes or the
 * GL context (and therefore this cache) is recreated. Oversized images are
 * downscaled before upload so they stay within `GL_MAX_TEXTURE_SIZE`.
 */
internal class GlesWallpaperTexture(
    private val api: WallpaperTextureApi = Gles30WallpaperTextureApi
) {
    private var textureId = 0
    private var uploadedId = Long.MIN_VALUE

    fun textureId(wallpaper: TerminalWallpaper, maxTextureSize: Int): Int? {
        if (textureId != 0 && uploadedId == wallpaper.id) return textureId
        return upload(wallpaper, maxTextureSize)
    }

    /**
     * Releases the cached texture when [wallpaper] is null and returns the
     * wallpaper unchanged otherwise. A null result tells the caller to skip
     * drawing, so a disabled or cleared wallpaper never retains GL memory.
     */
    fun releaseIfAbsent(wallpaper: TerminalWallpaper?): TerminalWallpaper? {
        if (wallpaper == null) release()
        return wallpaper
    }

    private fun upload(wallpaper: TerminalWallpaper, maxTextureSize: Int): Int? {
        releaseTexture()
        val scaled = downscaleArgb(wallpaper.argb, wallpaper.width, wallpaper.height, maxTextureSize)
        val generated = api.createTexture(scaled.width, scaled.height, rgbaBuffer(scaled.pixels))
        if (generated == 0) return null
        textureId = generated
        uploadedId = wallpaper.id
        return textureId
    }

    fun release() {
        releaseTexture()
    }

    private fun releaseTexture() {
        if (textureId == 0) return
        api.deleteTexture(textureId)
        textureId = 0
        uploadedId = Long.MIN_VALUE
    }

    private fun rgbaBuffer(pixels: IntArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4).order(ByteOrder.nativeOrder())
        for (argb in pixels) {
            val alpha = (argb ushr 24) and 0xFF
            buffer.put(premultiply((argb ushr 16) and 0xFF, alpha).toByte())
            buffer.put(premultiply((argb ushr 8) and 0xFF, alpha).toByte())
            buffer.put(premultiply(argb and 0xFF, alpha).toByte())
            buffer.put(alpha.toByte())
        }
        buffer.position(0)
        return buffer
    }

    private fun premultiply(channel: Int, alpha: Int): Int = (channel * alpha + 127) / 255
}

/** Real [WallpaperTextureApi] backed by GLES 3.0. */
internal object Gles30WallpaperTextureApi : WallpaperTextureApi {
    override fun createTexture(width: Int, height: Int, rgba: ByteBuffer): Int {
        drainGlErrors()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val generated = ids[0]
        if (generated == 0) return 0
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, generated)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MIN_FILTER,
            GLES30.GL_LINEAR_MIPMAP_LINEAR
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            rgba
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        if (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            GLES30.glDeleteTextures(1, intArrayOf(generated), 0)
            return 0
        }
        return generated
    }

    override fun deleteTexture(textureId: Int) {
        GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
    }

    private fun drainGlErrors() {
        while (GLES30.glGetError() != GLES30.GL_NO_ERROR) {
            // Drain stale errors so an upload success check observes only its own error.
        }
    }
}

/** GLES program that composites a wallpaper behind terminal content. */
internal class GlesWallpaperProgram private constructor(
    private val programId: Int,
    private val viewportUniform: Int,
    private val rectUniform: Int,
    private val texRectUniform: Int,
    private val textureUniform: Int,
    private val alphaUniform: Int
) {
    fun bind(
        viewportWidth: Int,
        viewportHeight: Int,
        textureId: Int,
        plan: WallpaperRectPlan,
        alpha: Float
    ) {
        GLES30.glUseProgram(programId)
        GLES30.glUniform2f(viewportUniform, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform4f(rectUniform, plan.left, plan.top, plan.right, plan.bottom)
        GLES30.glUniform4f(texRectUniform, plan.u0, plan.v0, plan.u1, plan.v1)
        GLES30.glUniform1f(alphaUniform, alpha)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureUniform, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    companion object {
        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        fun create(): GlesWallpaperProgram {
            val vertexShader = compile(
                "wallpaper-vertex",
                GLES30.GL_VERTEX_SHADER,
                GlesWallpaperShaderSources.VERTEX
            )
            val fragmentShader = try {
                compile(
                    "wallpaper-fragment",
                    GLES30.GL_FRAGMENT_SHADER,
                    GlesWallpaperShaderSources.FRAGMENT
                )
            } catch (error: GlesProgramException) {
                GLES30.glDeleteShader(vertexShader)
                throw error
            }
            val program = GLES30.glCreateProgram()
            if (program == 0) {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw GlesProgramException("glCreateProgram returned 0")
            }
            try {
                GLES30.glAttachShader(program, vertexShader)
                GLES30.glAttachShader(program, fragmentShader)
                GLES30.glLinkProgram(program)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    throw GlesProgramException(
                        "GLES wallpaper program link failed: ${GLES30.glGetProgramInfoLog(program)}"
                    )
                }
                val viewport = GLES30.glGetUniformLocation(program, "uViewport")
                val rect = GLES30.glGetUniformLocation(program, "uRect")
                val texRect = GLES30.glGetUniformLocation(program, "uTexRect")
                val texture = GLES30.glGetUniformLocation(program, "uTexture")
                val alpha = GLES30.glGetUniformLocation(program, "uAlpha")
                requireUniform(viewport, "uViewport")
                requireUniform(rect, "uRect")
                requireUniform(texRect, "uTexRect")
                requireUniform(texture, "uTexture")
                requireUniform(alpha, "uAlpha")
                return GlesWallpaperProgram(program, viewport, rect, texRect, texture, alpha)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteProgram(program)
                throw error
            } catch (error: RuntimeException) {
                GLES30.glDeleteProgram(program)
                throw error
            } finally {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
            }
        }

        private fun requireUniform(location: Int, name: String) {
            if (location < 0) throw GlesProgramException("GLES uniform is incomplete: $name")
        }

        private fun compile(tag: String, type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlesProgramException("glCreateShader returned 0")
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlesProgramException("$tag shader compile failed: $log")
            }
            return shader
        }
    }
}

internal object GlesWallpaperShaderSources {
    val VERTEX = """
        #version 300 es
        precision highp float;

        uniform vec2 uViewport;
        uniform vec4 uRect;
        uniform vec4 uTexRect;

        out vec2 vTexCoord;

        const vec2 QUAD_VERTICES[4] = vec2[4](
            vec2(0.0, 0.0),
            vec2(0.0, 1.0),
            vec2(1.0, 0.0),
            vec2(1.0, 1.0)
        );

        void main() {
            vec2 unit = QUAD_VERTICES[gl_VertexID];
            vec2 position = mix(uRect.xy, uRect.zw, unit);
            vec2 ndc = vec2(
                (position.x / uViewport.x) * 2.0 - 1.0,
                1.0 - (position.y / uViewport.y) * 2.0
            );
            gl_Position = vec4(ndc, 0.0, 1.0);
            vTexCoord = mix(uTexRect.xy, uTexRect.zw, unit);
        }
    """.trimIndent()

    val FRAGMENT = """
        #version 300 es
        precision mediump float;

        uniform sampler2D uTexture;
        uniform float uAlpha;

        in vec2 vTexCoord;
        out vec4 fragColor;

        void main() {
            vec4 sampled = texture(uTexture, vTexCoord);
            float coverage = sampled.a * uAlpha;
            fragColor = vec4(sampled.rgb * uAlpha, coverage);
        }
    """.trimIndent()
}

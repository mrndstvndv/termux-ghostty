package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import com.termux.terminal.compose.TerminalImagePlacement

@Suppress("LongMethod", "LoopWithTooManyJumpStatements")
internal data class GlesTextureEntry(
    val textureId: Int,
    val generation: Long,
    val width: Int,
    val height: Int
)

internal class GlesImageTextureCache {
    private val textures = mutableMapOf<Long, GlesTextureEntry>()

    @Suppress("LongMethod", "LoopWithTooManyJumpStatements")
    fun update(placements: List<TerminalImagePlacement>) {
        if (placements.isEmpty() && textures.isEmpty()) return
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        val activeIds = placements.map { it.imageId }.toSet()
        val toRemove = textures.keys - activeIds
        for (id in toRemove) {
            textures[id]?.let { entry ->
                GLES30.glDeleteTextures(1, intArrayOf(entry.textureId), 0)
            }
            textures.remove(id)
        }
        for (placement in placements) {
            val existing = textures[placement.imageId]
            if (existing != null && existing.generation == placement.imageGeneration) {
                continue
            }
            val buffer = placement.pixelBuffer ?: continue
            val width = placement.textureWidth
            val height = placement.textureHeight
            if (width <= 0 || height <= 0) continue
            val textureId = existing?.textureId ?: run {
                val ids = IntArray(1)
                GLES30.glGenTextures(1, ids, 0)
                ids[0]
            }
            if (textureId == 0) continue
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MIN_FILTER,
                GLES30.GL_LINEAR
            )
            GLES30.glTexParameteri(
                GLES30.GL_TEXTURE_2D,
                GLES30.GL_TEXTURE_MAG_FILTER,
                GLES30.GL_LINEAR
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
            buffer.position(0)
            val format = GLES30.GL_RGBA
            val internalFormat = format
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D,
                0,
                internalFormat,
                width,
                height,
                0,
                format,
                GLES30.GL_UNSIGNED_BYTE,
                buffer
            )
            textures[placement.imageId] = GlesTextureEntry(textureId, placement.imageGeneration, width, height)
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    fun textureId(imageId: Long): Int? = textures[imageId]?.textureId

    fun release() {
        if (textures.isEmpty()) return
        val ids = textures.values.map { it.textureId }.toIntArray()
        GLES30.glDeleteTextures(ids.size, ids, 0)
        textures.clear()
    }
}

@Suppress("TooManyFunctions", "LongParameterList")
internal class GlesImageProgram private constructor(
    private val programId: Int,
    private val viewportUniform: Int,
    private val rectUniform: Int,
    private val texRectUniform: Int,
    private val textureUniform: Int
) {
    fun bind(
        viewportWidth: Int,
        viewportHeight: Int,
        textureId: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float
    ) {
        GLES30.glUseProgram(programId)
        GLES30.glUniform2f(viewportUniform, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform4f(rectUniform, left, top, right, bottom)
        GLES30.glUniform4f(texRectUniform, u0, v0, u1, v1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glUniform1i(textureUniform, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    companion object {
        @Suppress("ThrowsCount")
        fun create(): GlesImageProgram {
            val vertexShader = compile("image-vertex", GLES30.GL_VERTEX_SHADER, GlesImageShaderSources.VERTEX)
            val fragmentShader = try {
                compile("image-fragment", GLES30.GL_FRAGMENT_SHADER, GlesImageShaderSources.FRAGMENT)
            } catch (e: GlesProgramException) {
                GLES30.glDeleteShader(vertexShader)
                throw e
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
                    throw GlesProgramException("GLES image program link failed: ${GLES30.glGetProgramInfoLog(program)}")
                }
                val viewport = GLES30.glGetUniformLocation(program, "uViewport")
                val rect = GLES30.glGetUniformLocation(program, "uRect")
                val texRect = GLES30.glGetUniformLocation(program, "uTexRect")
                val texture = GLES30.glGetUniformLocation(program, "uTexture")
                requireUniform(viewport, "uViewport")
                requireUniform(rect, "uRect")
                requireUniform(texRect, "uTexRect")
                requireUniform(texture, "uTexture")
                return GlesImageProgram(program, viewport, rect, texRect, texture)
            } catch (e: GlesProgramException) {
                GLES30.glDeleteProgram(program)
                throw e
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

internal object GlesImageShaderSources {
    const val VERTEX = """
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
    """

    const val FRAGMENT = """
        #version 300 es
        precision mediump float;
        uniform sampler2D uTexture;
        in vec2 vTexCoord;
        out vec4 fragColor;
        void main() {
            vec4 sampled = texture(uTexture, vTexCoord);
            fragColor = vec4(sampled.rgb * sampled.a, sampled.a);
        }
    """
}

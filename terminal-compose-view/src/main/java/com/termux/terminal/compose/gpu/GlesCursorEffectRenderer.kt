package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import com.termux.terminal.compose.internal.CursorEffectRenderPlan
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val CoordinatesPerVertex = 2
private const val MaxCursorVertices = 8
private const val CursorBufferBytes = MaxCursorVertices * CoordinatesPerVertex * Float.SIZE_BYTES

/** GL-thread-confined hardware pass for one convex cursor trail. */
internal class GlesCursorEffectRenderer private constructor(
    private val programId: Int,
    private val viewportUniform: Int,
    private val colorUniform: Int,
    private val cutoutUniform: Int,
    private val offsetUniform: Int,
    private val bufferId: Int
) {
    private val vertexBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(CursorBufferBytes)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
    private var released = false

    fun draw(
        plan: CursorEffectRenderPlan,
        viewportWidth: Int,
        viewportHeight: Int,
        offsetY: Float = 0f
    ) {
        check(!released) { "cursor effect renderer is released" }
        if (plan.vertexCount < 3) return

        vertexBuffer.clear()
        vertexBuffer.put(plan.vertices, 0, plan.vertexCount * CoordinatesPerVertex)
        vertexBuffer.flip()

        GLES30.glUseProgram(programId)
        GLES30.glUniform2f(viewportUniform, viewportWidth.toFloat(), viewportHeight.toFloat())
        GLES30.glUniform1f(offsetUniform, offsetY)
        val alpha = ((plan.argb ushr 24) and 0xFF) / 255f
        GLES30.glUniform4f(
            colorUniform,
            ((plan.argb ushr 16) and 0xFF) / 255f * alpha,
            ((plan.argb ushr 8) and 0xFF) / 255f * alpha,
            (plan.argb and 0xFF) / 255f * alpha,
            alpha
        )
        GLES30.glUniform4f(
            cutoutUniform,
            plan.cutoutLeft,
            plan.cutoutTop + offsetY,
            plan.cutoutRight,
            plan.cutoutBottom + offsetY
        )
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            plan.vertexCount * CoordinatesPerVertex * Float.SIZE_BYTES,
            vertexBuffer
        )
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(
            0,
            CoordinatesPerVertex,
            GLES30.GL_FLOAT,
            false,
            CoordinatesPerVertex * Float.SIZE_BYTES,
            0
        )
        GLES30.glVertexAttribDivisor(0, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_FAN, 0, plan.vertexCount)
    }

    fun release() {
        if (released) return
        released = true
        GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
        GLES30.glDeleteProgram(programId)
    }

    companion object {
        @Suppress("ThrowsCount")
        fun create(): GlesCursorEffectRenderer {
            val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, GlesShaderSources.CURSOR_VERTEX)
            val fragmentShader = try {
                compileShader(GLES30.GL_FRAGMENT_SHADER, GlesShaderSources.CURSOR_FRAGMENT)
            } catch (error: GlesProgramException) {
                GLES30.glDeleteShader(vertexShader)
                throw error
            }
            val program = GLES30.glCreateProgram()
            if (program == 0) {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
                throw GlesProgramException("cursor glCreateProgram returned 0")
            }
            var bufferId = 0
            try {
                GLES30.glAttachShader(program, vertexShader)
                GLES30.glAttachShader(program, fragmentShader)
                GLES30.glLinkProgram(program)
                val status = IntArray(1)
                GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
                if (status[0] == 0) {
                    throw GlesProgramException(
                        "cursor program link failed: ${GLES30.glGetProgramInfoLog(program)}"
                    )
                }
                val bufferIds = IntArray(1)
                GLES30.glGenBuffers(1, bufferIds, 0)
                bufferId = bufferIds[0]
                if (bufferId == 0) throw GlesResourceException("cursor buffer allocation returned 0")
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
                GLES30.glBufferData(
                    GLES30.GL_ARRAY_BUFFER,
                    CursorBufferBytes,
                    null,
                    GLES30.GL_DYNAMIC_DRAW
                )
                return GlesCursorEffectRenderer(
                    programId = program,
                    viewportUniform = requiredUniform(program, "uViewport"),
                    colorUniform = requiredUniform(program, "uColor"),
                    cutoutUniform = requiredUniform(program, "uCutout"),
                    offsetUniform = requiredUniform(program, "uOffsetY"),
                    bufferId = bufferId
                )
            } catch (error: GlesRendererException) {
                if (bufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
                GLES30.glDeleteProgram(program)
                throw error
            } finally {
                GLES30.glDeleteShader(vertexShader)
                GLES30.glDeleteShader(fragmentShader)
            }
        }

        private fun compileShader(type: Int, source: String): Int {
            val shader = GLES30.glCreateShader(type)
            if (shader == 0) throw GlesProgramException("cursor glCreateShader returned 0")
            GLES30.glShaderSource(shader, source)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                GLES30.glDeleteShader(shader)
                throw GlesProgramException("cursor shader compilation failed: $log")
            }
            return shader
        }

        private fun requiredUniform(program: Int, name: String): Int {
            val location = GLES30.glGetUniformLocation(program, name)
            if (location < 0) throw GlesProgramException("cursor uniform $name is unavailable")
            return location
        }
    }
}

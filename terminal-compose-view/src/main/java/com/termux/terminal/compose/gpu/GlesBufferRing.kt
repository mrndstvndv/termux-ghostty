package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import java.nio.FloatBuffer

private const val QuadVertexCount = 4

/** Keeps prepared instance data resident on the GPU between identical presentations. */
internal class GlesStaticInstanceBuffer {
    private var bufferId = 0
    private var capacityBytes = 0
    private var released = false

    fun upload(instanceBuffer: FloatBuffer, instanceCount: Int) {
        check(instanceCount > 0) { "instanceCount must be positive" }
        val byteCount = instanceBuffer.remaining() * Float.SIZE_BYTES
        check(byteCount > 0) { "instance data must not be empty" }
        ensureBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        // Orphan the old store so a plan update never overwrites data in flight.
        if (byteCount > capacityBytes) {
            capacityBytes = byteCount
        }
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            capacityBytes,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            0,
            byteCount,
            instanceBuffer
        )
    }

    fun draw(instanceCount: Int, configureAttributes: () -> Unit) {
        check(instanceCount > 0) { "instanceCount must be positive" }
        check(bufferId != 0) { "instance buffer has not been uploaded" }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        configureAttributes()
        GLES30.glDrawArraysInstanced(
            GLES30.GL_TRIANGLE_STRIP,
            0,
            QuadVertexCount,
            instanceCount
        )
    }

    fun release() {
        if (released) return
        released = true
        if (bufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
            bufferId = 0
        }
        capacityBytes = 0
    }

    private fun ensureBuffer() {
        check(!released) { "instance buffer is released" }
        if (bufferId != 0) return
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        if (ids[0] == 0) throw GlesResourceException("static-buffer: glGenBuffers returned 0")
        bufferId = ids[0]
    }
}

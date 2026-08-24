package com.termux.terminal.compose.gpu

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

private const val DirtyQuadVertexCount = 4
private const val RowCountTextureUnit = GLES30.GL_TEXTURE2

internal enum class GlesDirtyBufferUpdateStrategy {
    NONE,
    FRESH_STORAGE,
    ORPHAN_STORAGE,
    CLONE_STORAGE
}

internal fun glesDirtyBufferUpdateStrategy(
    geometryChanged: Boolean,
    changedRowCount: Int,
    rowCount: Int
): GlesDirtyBufferUpdateStrategy {
    return when {
        changedRowCount == 0 -> GlesDirtyBufferUpdateStrategy.NONE
        geometryChanged -> GlesDirtyBufferUpdateStrategy.FRESH_STORAGE
        changedRowCount == rowCount -> GlesDirtyBufferUpdateStrategy.ORPHAN_STORAGE
        else -> GlesDirtyBufferUpdateStrategy.CLONE_STORAGE
    }
}

/** Resident instance storage that updates only rows whose packet identity changed. */
internal class GlesDirtyInstanceBuffer {
    private var bufferId = 0
    private var capacityBytes = 0
    private var rowCount = 0
    private var rowStride = 0
    private var strideBytes = 0
    private var strideFloats = 0
    private var rowSources = arrayOfNulls<Any>(0)
    private var rowCounts = IntArray(0)
    private var nextSources = arrayOfNulls<Any>(0)
    private var nextCounts = IntArray(0)
    private var changedRows = BooleanArray(0)
    private var expandedScratch: FloatBuffer? = null
    private var rowCountTexture = GlesRowCountTexture()
    private var hasInstances = false
    private var released = false

    @Suppress("LongParameterList", "CyclomaticComplexMethod", "LongMethod")
    fun update(
        rowCount: Int,
        rowStride: Int,
        strideBytes: Int,
        strideFloats: Int,
        scratch: FloatBuffer,
        sourceFor: (Int) -> Any,
        countFor: (Int) -> Int,
        fillRow: (Int, FloatBuffer) -> Unit
    ) {
        require(rowCount >= 0) { "rowCount must not be negative" }
        require(rowStride > 0) { "rowStride must be positive" }
        require(strideBytes > 0) { "strideBytes must be positive" }
        require(strideFloats > 0) { "strideFloats must be positive" }
        check(!released) { "dirty instance buffer is released" }

        val geometryChanged = this.rowCount != rowCount ||
            this.rowStride != rowStride ||
            this.strideBytes != strideBytes ||
            this.strideFloats != strideFloats
        if (geometryChanged) {
            this.rowCount = rowCount
            this.rowStride = rowStride
            this.strideBytes = strideBytes
            this.strideFloats = strideFloats
            rowSources = arrayOfNulls(rowCount)
            rowCounts = IntArray(rowCount)
            nextSources = arrayOfNulls(rowCount)
            nextCounts = IntArray(rowCount)
            changedRows = BooleanArray(rowCount)
            hasInstances = false
            ensureStorage(rowCount * rowStride * strideBytes, forceReallocate = true)
        } else {
            ensureStorage(rowCount * rowStride * strideBytes, forceReallocate = false)
        }

        var changedRowCount = 0
        var countsChanged = geometryChanged
        hasInstances = false
        for (index in 0 until rowCount) {
            val source = sourceFor(index)
            val count = countFor(index)
            require(count in 0..rowStride) {
                "row instance count $count exceeds row stride $rowStride"
            }
            nextSources[index] = source
            nextCounts[index] = count
            changedRows[index] = geometryChanged ||
                rowCounts[index] != count || (count > 0 && rowSources[index] !== source)
            if (changedRows[index]) changedRowCount++
            countsChanged = countsChanged || rowCounts[index] != count
            hasInstances = hasInstances || count > 0
        }
        when (glesDirtyBufferUpdateStrategy(geometryChanged, changedRowCount, rowCount)) {
            GlesDirtyBufferUpdateStrategy.NONE -> return
            GlesDirtyBufferUpdateStrategy.FRESH_STORAGE -> Unit
            GlesDirtyBufferUpdateStrategy.ORPHAN_STORAGE -> orphanStorageForUpdate()
            GlesDirtyBufferUpdateStrategy.CLONE_STORAGE -> cloneStorageForUpdate()
        }
        for (index in 0 until rowCount) {
            if (!changedRows[index] || nextCounts[index] == 0) continue
            val rowBuffer = prepareScratch(scratch, rowStride * strideFloats)
            rowBuffer.clear()
            fillRow(index, rowBuffer)
            check(rowBuffer.position() == nextCounts[index] * strideFloats) {
                "row packing produced an unexpected instance count"
            }
            rowBuffer.flip()
            uploadRow(index, rowBuffer)
        }
        val previousSources = rowSources
        rowSources = nextSources
        nextSources = previousSources
        val previousCounts = rowCounts
        rowCounts = nextCounts
        nextCounts = previousCounts
        if (countsChanged && rowCount > 0) rowCountTexture.update(rowCounts)
    }

    fun hasInstances(): Boolean = hasInstances

    fun bindRowCounts() {
        rowCountTexture.bind()
    }

    fun draw(configureAttributes: () -> Unit) {
        if (!hasInstances) return
        check(bufferId != 0) { "dirty instance buffer has not been uploaded" }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        configureAttributes()
        GLES30.glDrawArraysInstanced(
            GLES30.GL_TRIANGLE_STRIP,
            0,
            DirtyQuadVertexCount,
            rowCount * rowStride
        )
    }

    fun release() {
        if (released) return
        released = true
        if (bufferId != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
            bufferId = 0
        }
        rowCountTexture.release()
        rowSources = emptyArray()
        nextSources = emptyArray()
        rowCounts = IntArray(0)
        nextCounts = IntArray(0)
        changedRows = BooleanArray(0)
        expandedScratch = null
    }

    private fun prepareScratch(scratch: FloatBuffer, requiredFloats: Int): FloatBuffer {
        if (scratch.capacity() >= requiredFloats) return scratch
        val cached = expandedScratch
        if (cached != null && cached.capacity() >= requiredFloats) return cached
        return ByteBuffer
            .allocateDirect(requiredFloats * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .also { expandedScratch = it }
    }

    private fun ensureStorage(requiredBytes: Int, forceReallocate: Boolean) {
        if (requiredBytes <= 0) {
            if (bufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(bufferId), 0)
            bufferId = 0
            capacityBytes = 0
            return
        }
        if (bufferId == 0) bufferId = generateBuffer()
        if (!forceReallocate && capacityBytes == requiredBytes) return
        capacityBytes = requiredBytes
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            capacityBytes,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
    }

    private fun orphanStorageForUpdate() {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            capacityBytes,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
    }

    private fun cloneStorageForUpdate() {
        val oldBufferId = bufferId
        bufferId = generateBuffer()
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            capacityBytes,
            null,
            GLES30.GL_DYNAMIC_DRAW
        )
        copyBuffer(oldBufferId, bufferId, capacityBytes)
        GLES30.glDeleteBuffers(1, intArrayOf(oldBufferId), 0)
    }

    private fun uploadRow(rowIndex: Int, rowBuffer: FloatBuffer) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufferId)
        GLES30.glBufferSubData(
            GLES30.GL_ARRAY_BUFFER,
            rowIndex * rowStride * strideBytes,
            rowBuffer.remaining() * Float.SIZE_BYTES,
            rowBuffer
        )
    }

    private fun copyBuffer(sourceId: Int, destinationId: Int, bytes: Int) {
        GLES30.glBindBuffer(GLES30.GL_COPY_READ_BUFFER, sourceId)
        GLES30.glBindBuffer(GLES30.GL_COPY_WRITE_BUFFER, destinationId)
        GLES30.glCopyBufferSubData(
            GLES30.GL_COPY_READ_BUFFER,
            GLES30.GL_COPY_WRITE_BUFFER,
            0,
            0,
            bytes
        )
    }

    private fun generateBuffer(): Int {
        val ids = IntArray(1)
        GLES30.glGenBuffers(1, ids, 0)
        if (ids[0] == 0) throw GlesResourceException("dirty-buffer: glGenBuffers returned 0")
        return ids[0]
    }
}

/** Small RGBA texture containing one exact 32-bit instance count per row. */
private class GlesRowCountTexture {
    private var textureId = 0
    private var lastCounts = IntArray(0)
    private var pixels = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    private var released = false

    fun update(counts: IntArray) {
        check(!released) { "row-count texture is released" }
        ensureTexture()
        if (lastCounts.contentEquals(counts)) return
        if (pixels.capacity() < counts.size * 4) {
            pixels = ByteBuffer
                .allocateDirect(counts.size * 4)
                .order(ByteOrder.nativeOrder())
        }
        pixels.clear()
        counts.forEach { count ->
            pixels.put((count and 0xFF).toByte())
            pixels.put(((count ushr 8) and 0xFF).toByte())
            pixels.put(((count ushr 16) and 0xFF).toByte())
            pixels.put(((count ushr 24) and 0xFF).toByte())
        }
        pixels.flip()
        GLES30.glActiveTexture(RowCountTextureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            counts.size,
            1,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            pixels
        )
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 4)
        lastCounts = counts.copyOf()
    }

    fun bind() {
        check(!released) { "row-count texture is released" }
        ensureTexture()
        GLES30.glActiveTexture(RowCountTextureUnit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
    }

    fun release() {
        if (released) return
        released = true
        if (textureId != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(textureId), 0)
            textureId = 0
        }
        lastCounts = IntArray(0)
    }

    private fun ensureTexture() {
        if (textureId != 0) return
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        if (ids[0] == 0) throw GlesResourceException("row-count-texture: glGenTextures returned 0")
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

/** Resident row buffers for the three non-atlas draw layers. */
internal class GlesDirtyInstanceStore {
    val backgrounds = GlesDirtyInstanceBuffer()
    val decorations = GlesDirtyInstanceBuffer()

    fun release() {
        backgrounds.release()
        decorations.release()
    }
}

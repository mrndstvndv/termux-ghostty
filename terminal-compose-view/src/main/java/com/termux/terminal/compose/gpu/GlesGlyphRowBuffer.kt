package com.termux.terminal.compose.gpu

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.IdentityHashMap

/** CPU-retained row packets synchronized to one fixed-stride GPU glyph buffer. */
internal class GlesGlyphRowBuffer {
    private val gpuBuffer = GlesStaticInstanceBuffer()
    private val rowCountsTexture = GlesRowCountTexture()
    private var frontBuffer = emptyFloatBuffer()
    private var backBuffer = emptyFloatBuffer()
    private var rowSources = arrayOfNulls<Any>(0)
    private var rowCounts = IntArray(0)
    private var rowCount = 0
    private var rowStride = 0
    private var strideFloats = 0
    private var atlasGeneration = Int.MIN_VALUE
    private var hasInstances = false
    private var gpuReady = false
    private var released = false

    @Suppress("LongParameterList")
    fun update(
        rowCount: Int,
        rowStride: Int,
        strideFloats: Int,
        atlasGeneration: () -> Int,
        sourceFor: (Int) -> Any,
        maximumCountFor: (Int) -> Int,
        fillRow: (Int, FloatBuffer) -> Int
    ) {
        require(rowCount >= 0) { "rowCount must not be negative" }
        require(rowStride > 0) { "rowStride must be positive" }
        require(strideFloats > 0) { "strideFloats must be positive" }
        check(!released) { "glyph row buffer is released" }

        val geometryChanged = this.rowCount != rowCount ||
            this.rowStride != rowStride ||
            this.strideFloats != strideFloats
        if (geometryChanged) resize(rowCount, rowStride, strideFloats)

        val nextSources = collectSources(rowCount, rowStride, sourceFor, maximumCountFor)

        val generationBeforePack = atlasGeneration()
        if (!geometryChanged && generationBeforePack == this.atlasGeneration &&
            nextSources.indices.all { rowSources[it] === nextSources[it] }
        ) {
            return
        }

        val previousRows = retainedRowsBySource(
            retain = !geometryChanged && generationBeforePack == this.atlasGeneration
        )
        val nextCounts = IntArray(rowCount)
        var packedGeneration = packRows(
            nextSources = nextSources,
            nextCounts = nextCounts,
            previousRows = previousRows,
            allowCopies = previousRows.isNotEmpty(),
            atlasGeneration = atlasGeneration,
            fillRow = fillRow
        )
        if (packedGeneration != generationBeforePack) {
            packedGeneration = packRows(
                nextSources = nextSources,
                nextCounts = nextCounts,
                previousRows = previousRows,
                allowCopies = false,
                atlasGeneration = atlasGeneration,
                fillRow = fillRow
            )
            check(packedGeneration == atlasGeneration()) {
                "visible glyphs exceeded the bounded atlas"
            }
        }

        uploadPackedRows(nextSources, nextCounts, packedGeneration)
    }

    fun hasInstances(): Boolean = hasInstances && gpuReady

    fun bindRowCounts() {
        rowCountsTexture.bind()
    }

    fun draw(configureAttributes: () -> Unit) {
        if (!hasInstances()) return
        gpuBuffer.draw(rowCount * rowStride, configureAttributes)
    }

    fun release() {
        if (released) return
        released = true
        gpuBuffer.release()
        rowCountsTexture.release()
        frontBuffer = emptyFloatBuffer()
        backBuffer = emptyFloatBuffer()
        rowSources = emptyArray()
        rowCounts = IntArray(0)
    }

    private fun uploadPackedRows(
        nextSources: Array<Any?>,
        nextCounts: IntArray,
        packedGeneration: Int
    ) {
        val previousFront = frontBuffer
        frontBuffer = backBuffer
        backBuffer = previousFront
        rowSources = nextSources
        rowCounts = nextCounts
        atlasGeneration = packedGeneration
        hasInstances = rowCounts.any { it > 0 }
        if (!hasInstances) {
            gpuReady = false
            return
        }

        val upload = frontBuffer.duplicate().apply {
            clear()
            limit(rowCount * rowStride * strideFloats)
        }
        gpuBuffer.upload(upload, rowCount * rowStride)
        rowCountsTexture.update(rowCounts)
        gpuReady = true
    }

    @Suppress("LongParameterList")
    private fun collectSources(
        rowCount: Int,
        rowStride: Int,
        sourceFor: (Int) -> Any,
        maximumCountFor: (Int) -> Int
    ): Array<Any?> = arrayOfNulls<Any>(rowCount).also { sources ->
        for (rowIndex in 0 until rowCount) {
            require(maximumCountFor(rowIndex) in 0..rowStride) {
                "row glyph count exceeds row stride"
            }
            sources[rowIndex] = sourceFor(rowIndex)
        }
    }

    private fun retainedRowsBySource(retain: Boolean): IdentityHashMap<Any, Int> {
        val rowsBySource = IdentityHashMap<Any, Int>(rowCount)
        if (!retain) return rowsBySource
        rowSources.forEachIndexed { index, source ->
            if (source != null) rowsBySource[source] = index
        }
        return rowsBySource
    }

    private fun resize(rowCount: Int, rowStride: Int, strideFloats: Int) {
        this.rowCount = rowCount
        this.rowStride = rowStride
        this.strideFloats = strideFloats
        val capacity = rowCount * rowStride * strideFloats
        frontBuffer = allocateFloatBuffer(capacity)
        backBuffer = allocateFloatBuffer(capacity)
        rowSources = arrayOfNulls(rowCount)
        rowCounts = IntArray(rowCount)
        atlasGeneration = Int.MIN_VALUE
        hasInstances = false
        gpuReady = false
    }

    private fun packRows(
        nextSources: Array<Any?>,
        nextCounts: IntArray,
        previousRows: IdentityHashMap<Any, Int>,
        allowCopies: Boolean,
        atlasGeneration: () -> Int,
        fillRow: (Int, FloatBuffer) -> Int
    ): Int {
        val generationAtStart = atlasGeneration()
        for (rowIndex in 0 until rowCount) {
            val source = checkNotNull(nextSources[rowIndex])
            val previousIndex = previousRows[source]
            if (allowCopies && previousIndex != null) {
                nextCounts[rowIndex] = rowCounts[previousIndex]
                copyRow(previousIndex, rowIndex, nextCounts[rowIndex])
                continue
            }

            val destination = rowSlice(backBuffer, rowIndex)
            val count = fillRow(rowIndex, destination)
            require(count in 0..rowStride) { "packed glyph count exceeds row stride" }
            check(destination.position() == count * strideFloats) {
                "glyph row packing produced an unexpected instance count"
            }
            nextCounts[rowIndex] = count
        }
        return if (atlasGeneration() == generationAtStart) generationAtStart else atlasGeneration()
    }

    private fun copyRow(sourceRow: Int, destinationRow: Int, count: Int) {
        if (count == 0) return
        val sourceOffset = sourceRow * rowStride * strideFloats
        val source = frontBuffer.duplicate().apply {
            position(sourceOffset)
            limit(sourceOffset + count * strideFloats)
        }
        val destinationOffset = destinationRow * rowStride * strideFloats
        backBuffer.duplicate().apply {
            position(destinationOffset)
            put(source)
        }
    }

    private fun rowSlice(buffer: FloatBuffer, rowIndex: Int): FloatBuffer {
        val offset = rowIndex * rowStride * strideFloats
        return buffer.duplicate().apply {
            position(offset)
            limit(offset + rowStride * strideFloats)
        }.slice()
    }

    private fun allocateFloatBuffer(capacity: Int): FloatBuffer = ByteBuffer
        .allocateDirect(capacity * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    private fun emptyFloatBuffer(): FloatBuffer = allocateFloatBuffer(0)
}

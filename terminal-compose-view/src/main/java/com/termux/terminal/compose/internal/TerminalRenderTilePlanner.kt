package com.termux.terminal.compose.internal

private const val DefaultRowsPerRenderTile = 4

/** One retained layer's viewport rows. */
internal data class TerminalRenderTile(
    val index: Int,
    val rows: IntRange
)

/**
 * Reusable rendering work produced from row-level damage.
 *
 * Production callers use the indexed accessors to avoid allocating tile
 * objects on every frame. [tiles] is a diagnostic/test view of the same work.
 */
internal class TerminalRenderTileWork internal constructor(
    private var rowsPerTile: Int
) {
    private var dirtyTileIndices = IntArray(0)
    private var visibleRows = 0

    var recordedLayerCount: Int = 0
        private set

    var recordedRowCount: Int = 0
        private set

    val tiles: List<TerminalRenderTile>
        get() = List(recordedLayerCount) { workIndex ->
            val tileIndex = tileIndexAt(workIndex)
            TerminalRenderTile(tileIndex, firstRow(tileIndex)..<endRowExclusive(tileIndex))
        }

    internal fun update(
        rowsPerTile: Int,
        visibleRows: Int,
        dirtyTileIndices: IntArray,
        recordedLayerCount: Int
    ) {
        this.rowsPerTile = rowsPerTile
        this.visibleRows = visibleRows
        this.dirtyTileIndices = dirtyTileIndices
        this.recordedLayerCount = recordedLayerCount
        recordedRowCount = 0
        repeat(recordedLayerCount) { workIndex ->
            val tileIndex = tileIndexAt(workIndex)
            recordedRowCount += endRowExclusive(tileIndex) - firstRow(tileIndex)
        }
    }

    internal fun tileIndexAt(workIndex: Int): Int = dirtyTileIndices[workIndex]

    internal fun firstRow(tileIndex: Int): Int = tileIndex * rowsPerTile

    internal fun endRowExclusive(tileIndex: Int): Int =
        ((tileIndex + 1) * rowsPerTile).coerceAtMost(visibleRows)
}

/** Groups dirty rows into fixed-height retained tiles without per-frame allocations. */
internal class TerminalRenderTilePlanner(
    private val rowsPerTile: Int = DefaultRowsPerRenderTile
) {
    private var dirtyTileFlags = BooleanArray(0)
    private var dirtyTileIndices = IntArray(0)
    private val work = TerminalRenderTileWork(rowsPerTile)

    init {
        require(rowsPerTile > 0) { "rowsPerTile must be positive" }
    }

    fun plan(visibleRows: Int, dirtyRows: BooleanArray): TerminalRenderTileWork {
        require(visibleRows >= 0) { "visibleRows must not be negative" }
        require(dirtyRows.size >= visibleRows) { "dirtyRows must cover every visible row" }
        val tileCount = (visibleRows + rowsPerTile - 1) / rowsPerTile
        ensureCapacity(tileCount)
        dirtyTileFlags.fill(false, 0, tileCount)

        repeat(visibleRows) { rowIndex ->
            if (dirtyRows[rowIndex]) dirtyTileFlags[rowIndex / rowsPerTile] = true
        }

        var dirtyTileCount = 0
        repeat(tileCount) { tileIndex ->
            if (dirtyTileFlags[tileIndex]) {
                dirtyTileIndices[dirtyTileCount++] = tileIndex
            }
        }
        work.update(rowsPerTile, visibleRows, dirtyTileIndices, dirtyTileCount)
        return work
    }

    internal fun tileCount(visibleRows: Int): Int = (visibleRows + rowsPerTile - 1) / rowsPerTile

    internal fun firstRow(tileIndex: Int): Int = tileIndex * rowsPerTile

    internal fun endRowExclusive(tileIndex: Int, visibleRows: Int): Int =
        ((tileIndex + 1) * rowsPerTile).coerceAtMost(visibleRows)

    private fun ensureCapacity(tileCount: Int) {
        if (dirtyTileFlags.size >= tileCount) return
        dirtyTileFlags = BooleanArray(tileCount)
        dirtyTileIndices = IntArray(tileCount)
    }
}

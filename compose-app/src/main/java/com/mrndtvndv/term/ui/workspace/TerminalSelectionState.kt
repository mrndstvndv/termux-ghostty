package com.mrndtvndv.term.ui.workspace

data class TerminalSelectionState(
    var startCol: Int = -1,
    var startRow: Int = -1,
    var endCol: Int = -1,
    var endRow: Int = -1,
    var isActive: Boolean = false
) {
    fun reset() {
        startCol = -1
        startRow = -1
        endCol = -1
        endRow = -1
        isActive = false
    }
}

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import com.termux.terminal.TerminalSession
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.delay
import android.content.Context
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences
import kotlin.math.max
import kotlin.math.min

@Composable
fun TerminalCanvas(
    session: TerminalSession,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    var snapshot by remember { mutableStateOf(session.ghosttyPublishedFrameDelta?.transportSnapshot) }
    var viewportLinks by remember { mutableStateOf(session.ghosttyPublishedFrameDelta?.viewportLinkSnapshot) }
    
    // Quick polling to get snapshot updates (simulate StateFlow)
    LaunchedEffect(session) {
        while (true) {
            val delta = session.ghosttyPublishedFrameDelta
            val newSnapshot = delta?.transportSnapshot
            val newLinks = delta?.viewportLinkSnapshot
            if (newSnapshot != snapshot) {
                snapshot = newSnapshot
            }
            if (newLinks != viewportLinks) {
                viewportLinks = newLinks
            }
            delay(16) // ~60fps
        }
    }

    val paint = remember {
        Paint().apply {
            typeface = Typeface.MONOSPACE
            val sharedPreferences = context.getSharedPreferences("ssh_prefs", Context.MODE_PRIVATE)
            val sizes = TermuxAppSharedPreferences.getDefaultFontSizes(context)
            textSize = sharedPreferences.getInt("font_size", sizes[0]).toFloat()
            isAntiAlias = true
        }
    }
    
    val bgPaint = remember { Paint().apply { isAntiAlias = false } }
    var cellWidth by remember { mutableStateOf(0f) }
    var cellHeight by remember { mutableStateOf(0f) }

    var selectionState by remember { mutableStateOf(TerminalSelectionState()) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        if (cellWidth > 0 && cellHeight > 0) {
                            val col = (offset.x / cellWidth).toInt()
                            val row = (offset.y / cellHeight).toInt()
                            viewportLinks?.let { links ->
                                for (i in 0 until links.segmentCount) {
                                    val link = links.getSegment(i)
                                    // very simple box check
                                    if (row == link.row && col >= link.startColumn && col < link.endColumnExclusive) {
                                        onOpenUrl(link.url)
                                        return@detectTapGestures
                                    }
                                }
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        if (cellWidth > 0 && cellHeight > 0) {
                            val col = (offset.x / cellWidth).toInt()
                            val row = (offset.y / cellHeight).toInt()
                            selectionState = TerminalSelectionState(
                                startCol = col, startRow = row,
                                endCol = col, endRow = row,
                                isActive = true
                            )
                        }
                    },
                    onDrag = { change, dragAmount ->
                        if (selectionState.isActive && cellWidth > 0 && cellHeight > 0) {
                            val col = (change.position.x / cellWidth).toInt()
                            val row = (change.position.y / cellHeight).toInt()
                            selectionState = selectionState.copy(endCol = col, endRow = row)
                        }
                    },
                    onDragEnd = {
                        // On drag end, copy to clipboard
                        if (selectionState.isActive) {
                            val currentSnapshot = snapshot
                            if (currentSnapshot != null) {
                                val startR = min(selectionState.startRow, selectionState.endRow)
                                val endR = max(selectionState.startRow, selectionState.endRow)
                                val startC = if (startR == selectionState.startRow) selectionState.startCol else selectionState.endCol
                                val endC = if (endR == selectionState.endRow) selectionState.endCol else selectionState.startCol

                                val sb = StringBuilder()
                                for (rowIndex in startR..endR) {
                                    if (rowIndex < 0 || rowIndex >= currentSnapshot.rows) continue
                                    val row = currentSnapshot.getRow(rowIndex)
                                    val charsUsed = row.charsUsed
                                    val c1 = if (rowIndex == startR) max(0, startC) else 0
                                    val c2 = if (rowIndex == endR) min(charsUsed, endC) else charsUsed
                                    if (c1 < c2 && c1 < charsUsed) {
                                        sb.append(String(row.text).substring(c1, min(c2 + 1, charsUsed)))
                                    }
                                    if (rowIndex < endR && !row.isLineWrap) {
                                        sb.append("\n")
                                    }
                                }
                                if (sb.isNotEmpty()) {
                                    clipboardManager.setText(AnnotatedString(sb.toString()))
                                }
                            }
                        }
                        selectionState = selectionState.copy(isActive = false)
                    }
                )
            }
            .onSizeChanged { size ->
                val fm = paint.fontMetrics
                cellHeight = fm.descent - fm.ascent
                cellWidth = paint.measureText("W")
                if (cellWidth > 0 && cellHeight > 0) {
                    val cols = (size.width / cellWidth).toInt()
                    val rows = (size.height / cellHeight).toInt()
                    session.updateSize(cols, rows, cellWidth.toInt(), cellHeight.toInt())
                }
            }
    ) {
        val currentSnapshot = snapshot ?: return@Canvas
        
        drawIntoCanvas { canvas ->
            val nativeCanvas = canvas.nativeCanvas
            bgPaint.color = currentSnapshot.getPaletteColor(258) // Default bg
            nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgPaint)
            
            val fm = paint.fontMetrics
            val baselineOffset = -fm.ascent

            // Draw selection highlight
            if (selectionState.isActive) {
                bgPaint.color = android.graphics.Color.argb(128, 0, 150, 255)
                val startR = min(selectionState.startRow, selectionState.endRow)
                val endR = max(selectionState.startRow, selectionState.endRow)
                val startC = if (startR == selectionState.startRow) selectionState.startCol else selectionState.endCol
                val endC = if (endR == selectionState.endRow) selectionState.endCol else selectionState.startCol

                for (r in startR..endR) {
                    val c1 = if (r == startR) startC else 0
                    val c2 = if (r == endR) endC else (size.width / cellWidth).toInt()
                    val x1 = c1 * cellWidth
                    val x2 = (c2 + 1) * cellWidth
                    val y1 = r * cellHeight
                    val y2 = (r + 1) * cellHeight
                    nativeCanvas.drawRect(x1, y1, x2, y2, bgPaint)
                }
            }

            for (rowIndex in 0 until currentSnapshot.rows) {
                val row = currentSnapshot.getRow(rowIndex)
                val charsUsed = row.charsUsed
                if (charsUsed > 0) {
                    val text = String(row.text)
                    paint.color = android.graphics.Color.WHITE
                    nativeCanvas.drawText(
                        text, 0, charsUsed,
                        0f, rowIndex * cellHeight + baselineOffset, paint
                    )
                }
            }

            // Draw cursor
            if (currentSnapshot.isCursorVisible) {
                bgPaint.color = android.graphics.Color.WHITE
                val cx = currentSnapshot.cursorCol * cellWidth
                val cy = currentSnapshot.cursorRow * cellHeight
                nativeCanvas.drawRect(cx, cy, cx + cellWidth, cy + cellHeight, bgPaint)
            }
        }
    }
}

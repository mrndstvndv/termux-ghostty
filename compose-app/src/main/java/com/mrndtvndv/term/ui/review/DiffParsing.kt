package com.mrndtvndv.term.ui.review

import com.mrndtvndv.term.ui.CodeMatch
import com.mrndtvndv.term.ui.syntaxHighlightRanges

/**
 * Pure diff computation: parsing git's unified diff output into lines,
 * grouping paired deletions/additions, and computing word-level diffs.
 * Nothing here touches Compose state, so callers run it on a background
 * dispatcher (see ReviewViewModel.loadDiff).
 */

internal data class ParsedDiffLine(
    val text: String,
    val oldLineNum: String,
    val newLineNum: String,
    val type: DiffLineType,
    /**
     * Syntax-highlight token ranges over `text.drop(1)` (the content without
     * the `+`/`-`/` ` prefix). Empty for metadata and hunk-header lines,
     * which are never syntax-highlighted.
     */
    val highlightRanges: List<CodeMatch> = emptyList()
)

internal enum class DiffLineType {
    METADATA,
    HUNK_HEADER,
    CONTEXT,
    ADDITION,
    DELETION
}

internal data class FileDiffSection(
    val filePath: String,
    val lines: List<ParsedDiffLine>
)

internal data class TextMatch(
    val start: Int,
    val endExclusive: Int
)

internal fun findTextMatches(text: String, query: String): List<TextMatch> {
    if (text.isEmpty() || query.isEmpty()) return emptyList()

    val matches = mutableListOf<TextMatch>()
    var searchStart = 0
    while (searchStart <= text.length) {
        val start = text.indexOf(query, searchStart, ignoreCase = true)
        if (start < 0) break

        val endExclusive = start + query.length
        matches.add(TextMatch(start, endExclusive))
        searchStart = endExclusive
    }
    return matches
}

@Suppress("LongMethod")
private fun parseDiffLines(diffText: String): List<ParsedDiffLine> {
    var currentOldLine = 0
    var currentNewLine = 0
    return diffText.split("\n").map { line ->
        when {
            line.startsWith("@@ ") -> {
                val match = Regex("""@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""").find(line)
                if (match != null) {
                    currentOldLine = match.groupValues[1].toInt()
                    currentNewLine = match.groupValues[2].toInt()
                }
                ParsedDiffLine(line, "", "", DiffLineType.HUNK_HEADER)
            }
            line.startsWith("+") && !line.startsWith("+++") -> {
                val lineNum = currentNewLine.toString()
                currentNewLine++
                ParsedDiffLine(
                    line, "", lineNum, DiffLineType.ADDITION,
                    syntaxHighlightRanges(line.drop(1))
                )
            }
            line.startsWith("-") && !line.startsWith("---") -> {
                val lineNum = currentOldLine.toString()
                currentOldLine++
                ParsedDiffLine(
                    line, lineNum, "", DiffLineType.DELETION,
                    syntaxHighlightRanges(line.drop(1))
                )
            }
            line.startsWith(" ") -> {
                val oldNum = currentOldLine.toString()
                val newNum = currentNewLine.toString()
                currentOldLine++
                currentNewLine++
                ParsedDiffLine(
                    line, oldNum, newNum, DiffLineType.CONTEXT,
                    syntaxHighlightRanges(line.drop(1))
                )
            }
            line.startsWith("diff --git") || line.startsWith("index ") ||
                line.startsWith("--- ") || line.startsWith("+++ ") ||
                line.startsWith("\\ ") -> {
                ParsedDiffLine(line, "", "", DiffLineType.METADATA)
            }
            else -> {
                if (currentOldLine > 0) {
                    val oldNum = currentOldLine.toString()
                    val newNum = currentNewLine.toString()
                    currentOldLine++
                    currentNewLine++
                    ParsedDiffLine(
                        line, oldNum, newNum, DiffLineType.CONTEXT,
                        syntaxHighlightRanges(line.drop(1))
                    )
                } else {
                    ParsedDiffLine(line, "", "", DiffLineType.METADATA)
                }
            }
        }
    }
}

@Suppress("NestedBlockDepth")
internal fun parseFileDiffSections(diffText: String): List<FileDiffSection> {
    if (diffText.isBlank()) return emptyList()

    val rawLines = diffText.split("\n")
    val sections = mutableListOf<FileDiffSection>()
    var currentPath = ""
    var currentLines = mutableListOf<String>()

    fun flushSection() {
        if (currentLines.isNotEmpty()) {
            val parsed = parseDiffLines(currentLines.joinToString("\n"))
            val path = currentPath.ifBlank { "Diff Details" }
            sections.add(FileDiffSection(path, parsed))
            currentLines = mutableListOf()
            currentPath = ""
        }
    }

    rawLines.forEach { line ->
        if (line.startsWith("diff --git ")) {
            flushSection()
            val bPath = if (line.contains(" b/")) line.substringAfter(" b/").trim() else ""
            val aPath = if (line.contains(" a/")) {
                line.substringAfter(" a/").substringBefore(" b/").trim()
            } else ""
            currentPath = bPath.ifEmpty { aPath }
            currentLines.add(line)
        } else {
            if (currentPath.isEmpty()) {
                if (line.startsWith("+++ b/")) {
                    currentPath = line.substringAfter("+++ b/").trim()
                } else if (line.startsWith("--- a/")) {
                    currentPath = line.substringAfter("--- a/").trim()
                }
            }
            currentLines.add(line)
        }
    }
    flushSection()

    return sections.ifEmpty {
        listOf(FileDiffSection("Diff Details", parseDiffLines(diffText)))
    }
}

internal sealed class DiffRowGroup {
    class Single(val line: ParsedDiffLine) : DiffRowGroup()
    @Suppress("LongParameterList")
    class WordDiffPair(
        val oldLine: ParsedDiffLine,
        val newLine: ParsedDiffLine,
        val oldTokens: List<String>,
        val newTokens: List<String>,
        val oldUnchanged: BooleanArray,
        val newUnchanged: BooleanArray,
        val oldRanges: List<CodeMatch>,
        val newRanges: List<CodeMatch>
    ) : DiffRowGroup()
}

private const val MaxWordDiffTokens = 400

private val WordTokenRegex = Regex("[\\p{L}\\p{N}_]+|[^\\p{L}\\p{N}_]+")

private fun tokenizeForWordDiff(text: String): List<String> =
    WordTokenRegex.findAll(text).map { it.value }.toList()

/**
 * Token-level diff used for intra-line highlighting. Splits both lines into
 * alphanumeric runs and separators, then runs an LCS to mark which tokens are
 * unchanged. Returns null when the lines are too long to diff efficiently or
 * when either side is empty (nothing to highlight against).
 */
internal fun computeWordDiff(oldText: String, newText: String): WordDiffTokens? {
    val oldTokens = tokenizeForWordDiff(oldText)
    val newTokens = tokenizeForWordDiff(newText)
    if (oldTokens.isEmpty() || newTokens.isEmpty()) return null
    if (oldTokens.size > MaxWordDiffTokens || newTokens.size > MaxWordDiffTokens) return null

    // LCS table, flat-indexed to avoid nested allocation.
    val width = newTokens.size + 1
    val dp = IntArray((oldTokens.size + 1) * width)
    for (i in oldTokens.size - 1 downTo 0) {
        val row = i * width
        val nextRow = (i + 1) * width
        for (j in newTokens.size - 1 downTo 0) {
            dp[row + j] = if (oldTokens[i] == newTokens[j]) {
                dp[nextRow + j + 1] + 1
            } else {
                maxOf(dp[nextRow + j], dp[row + j + 1])
            }
        }
    }

    // Backtrack to find which tokens participate in the LCS.
    val oldUnchanged = BooleanArray(oldTokens.size)
    val newUnchanged = BooleanArray(newTokens.size)
    var i = 0
    var j = 0
    while (i < oldTokens.size && j < newTokens.size) {
        if (oldTokens[i] == newTokens[j]) {
            oldUnchanged[i] = true
            newUnchanged[j] = true
            i++
            j++
        } else if (dp[(i + 1) * width + j] >= dp[i * width + j + 1]) {
            i++
        } else {
            j++
        }
    }
    return WordDiffTokens(oldTokens, newTokens, oldUnchanged, newUnchanged)
}

internal class WordDiffTokens(
    val oldTokens: List<String>,
    val newTokens: List<String>,
    val oldUnchanged: BooleanArray,
    val newUnchanged: BooleanArray
)

/**
 * Groups parsed diff lines for rendering. Runs of deletions followed by
 * additions (or vice versa) are paired up positionally so each pair can be
 * rendered with word-level highlighting; unpaired and non-code lines stay
 * single rows. Every parsed line maps to exactly one rendered row, keeping the
 * line-number column aligned.
 */
internal fun groupDiffRows(lines: List<ParsedDiffLine>): List<DiffRowGroup> {
    val groups = mutableListOf<DiffRowGroup>()
    var i = 0
    while (i < lines.size) {
        i = appendGroup(groups, lines, i)
    }
    return groups
}

private fun appendGroup(
    groups: MutableList<DiffRowGroup>,
    lines: List<ParsedDiffLine>,
    start: Int
): Int {
    val type = lines[start].type
    if (type != DiffLineType.DELETION && type != DiffLineType.ADDITION) {
        groups.add(DiffRowGroup.Single(lines[start]))
        return start + 1
    }
    val (run, runEnd) = collectRun(lines, start, type)
    val oppositeType =
        if (type == DiffLineType.DELETION) DiffLineType.ADDITION else DiffLineType.DELETION
    val (oppositeRun, oppositeEnd) = collectRun(lines, runEnd, oppositeType)
    if (oppositeRun.isEmpty()) {
        run.forEach { groups.add(DiffRowGroup.Single(it)) }
        return runEnd
    }
    val oldLines = if (type == DiffLineType.DELETION) run else oppositeRun
    val newLines = if (type == DiffLineType.DELETION) oppositeRun else run
    addPairedRows(groups, oldLines, newLines)
    return oppositeEnd
}

private fun collectRun(
    lines: List<ParsedDiffLine>,
    start: Int,
    type: DiffLineType
): Pair<List<ParsedDiffLine>, Int> {
    val run = mutableListOf<ParsedDiffLine>()
    var i = start
    while (i < lines.size && lines[i].type == type) {
        run.add(lines[i])
        i++
    }
    return run to i
}

private fun addPairedRows(
    groups: MutableList<DiffRowGroup>,
    oldLines: List<ParsedDiffLine>,
    newLines: List<ParsedDiffLine>
) {
    val pairCount = minOf(oldLines.size, newLines.size)
    for (k in 0 until pairCount) {
        val oldText = oldLines[k].text.drop(1)
        val newText = newLines[k].text.drop(1)
        val wordDiff = computeWordDiff(oldText, newText)
        if (wordDiff != null) {
            groups.add(
                DiffRowGroup.WordDiffPair(
                    oldLines[k], newLines[k],
                    wordDiff.oldTokens, wordDiff.newTokens,
                    wordDiff.oldUnchanged, wordDiff.newUnchanged,
                    syntaxHighlightRanges(oldText), syntaxHighlightRanges(newText)
                )
            )
        } else {
            groups.add(DiffRowGroup.Single(oldLines[k]))
            groups.add(DiffRowGroup.Single(newLines[k]))
        }
    }
    if (oldLines.size > pairCount) {
        oldLines.drop(pairCount).forEach { groups.add(DiffRowGroup.Single(it)) }
    }
    if (newLines.size > pairCount) {
        newLines.drop(pairCount).forEach { groups.add(DiffRowGroup.Single(it)) }
    }
}

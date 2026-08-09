package com.mrndtvndv.term.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WordDiffTest {

    private fun tokens(result: WordDiffTokens): Pair<List<String>, List<String>> =
        result.oldTokens to result.newTokens

    private fun parse(vararg lines: String): List<ParsedDiffLine> = lines.map { text ->
        when {
            text.startsWith("+") -> ParsedDiffLine(text, "", "", DiffLineType.ADDITION)
            text.startsWith("-") -> ParsedDiffLine(text, "", "", DiffLineType.DELETION)
            text.startsWith("\\") -> ParsedDiffLine(text, "", "", DiffLineType.METADATA)
            else -> ParsedDiffLine(text, "", "", DiffLineType.CONTEXT)
        }
    }

    /** A pair group renders two rows, a single renders one. */
    private fun renderedRowCount(groups: List<DiffRowGroup>): Int =
        groups.sumOf { if (it is DiffRowGroup.WordDiffPair) 2 else 1 }

    @Test
    fun `identical lines are fully unchanged`() {
        val result = computeWordDiff("val a = 1", "val a = 1")!!
        assertEquals(listOf("val", " ", "a", " = ", "1"), tokens(result).first)
        assertTrue(result.oldUnchanged.all { it })
        assertTrue(result.newUnchanged.all { it })
    }

    @Test
    fun `single changed word is flagged on both sides`() {
        val result = computeWordDiff("val name = 1", "val count = 1")!!
        assertEquals(listOf("val", " ", "name", " = ", "1"), tokens(result).first)
        assertEquals(listOf("val", " ", "count", " = ", "1"), tokens(result).second)
        assertEquals(
            listOf(true, true, false, true, true),
            result.oldUnchanged.toList()
        )
        assertEquals(
            listOf(true, true, false, true, true),
            result.newUnchanged.toList()
        )
    }

    @Test
    fun `inserted tokens are flagged only on the new side`() {
        val result = computeWordDiff("foo bar", "foo bar baz")!!
        assertTrue(result.oldUnchanged.all { it })
        assertEquals(listOf(true, true, true, false, false), result.newUnchanged.toList())
    }

    @Test
    fun `empty line on either side returns null`() {
        assertNull(computeWordDiff("", "foo"))
        assertNull(computeWordDiff("foo", ""))
        assertNull(computeWordDiff("", ""))
    }

    @Test
    fun `lines exceeding token cap return null`() {
        val long = (0..500).joinToString(" ") { "word$it" }
        assertNull(computeWordDiff(long, long))
    }

    @Test
    fun `adjacent deletion and addition become a word diff pair`() {
        val groups = groupDiffRows(parse("-old line", "+new line", " context"))
        assertEquals(2, groups.size)
        assertTrue(groups[0] is DiffRowGroup.WordDiffPair)
        assertTrue(groups[1] is DiffRowGroup.Single)
        assertEquals(3, renderedRowCount(groups))
    }

    @Test
    fun `rendered rows always match parsed line count`() {
        val lines = parse("-a", "-b", "+c", "+d", " ctx")
        val groups = groupDiffRows(lines)
        assertEquals(3, groups.size)
        assertTrue(groups[0] is DiffRowGroup.WordDiffPair)
        assertTrue(groups[1] is DiffRowGroup.WordDiffPair)
        assertTrue(groups[2] is DiffRowGroup.Single)
        assertEquals(5, renderedRowCount(groups))
    }

    @Test
    fun `unmatched extras stay single rows`() {
        val groups = groupDiffRows(parse("-a", "+b", "+c"))
        assertEquals(2, groups.size)
        assertTrue(groups[0] is DiffRowGroup.WordDiffPair)
        assertTrue(groups[1] is DiffRowGroup.Single)
        assertEquals(3, renderedRowCount(groups))
    }

    @Test
    fun `deletion without following addition stays single`() {
        val groups = groupDiffRows(parse("-only deleted", " ctx"))
        assertEquals(2, groups.size)
        assertTrue(groups.all { it is DiffRowGroup.Single })
    }

    @Test
    fun `addition followed by deletion also pairs`() {
        val groups = groupDiffRows(parse("+new", "-old"))
        assertEquals(1, groups.size)
        val pair = groups[0] as DiffRowGroup.WordDiffPair
        assertEquals("old", pair.oldLine.text.drop(1))
        assertEquals("new", pair.newLine.text.drop(1))
    }

    @Test
    fun `non-adjacent change lines do not pair`() {
        val groups = groupDiffRows(parse("-a", "\\ No newline at end of file", "+b"))
        assertEquals(3, groups.size)
        assertTrue(groups.all { it is DiffRowGroup.Single })
    }
}

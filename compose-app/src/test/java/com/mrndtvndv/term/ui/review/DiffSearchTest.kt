package com.mrndtvndv.term.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiffSearchTest {

    @Test
    fun `finds case insensitive non overlapping matches`() {
        assertEquals(
            listOf(TextMatch(0, 3), TextMatch(4, 7)),
            findTextMatches("Foo foo", "FOO")
        )
    }

    @Test
    fun `empty query has no matches`() {
        assertTrue(findTextMatches("content", "").isEmpty())
    }

    @Test
    fun `query longer than text has no matches`() {
        assertTrue(findTextMatches("foo", "foobar").isEmpty())
    }
}

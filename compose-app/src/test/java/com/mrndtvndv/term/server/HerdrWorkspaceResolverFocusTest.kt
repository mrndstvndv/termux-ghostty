package com.mrndtvndv.term.server

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrWorkspaceResolverFocusTest {

    private fun resolver(execCommand: suspend (String) -> String): HerdrWorkspaceResolver =
        HerdrWorkspaceResolver(execCommand)

    // ── parseFocusTarget ─────────────────────────────────────────────

    @Test
    fun `parses workspace-only body`() {
        val target = resolver { "" }.parseFocusTarget("myproj · 2")
        assertEquals(HerdrWorkspaceResolver.HerdrFocusTarget(2), target)
    }

    @Test
    fun `parses workspace with unnamed tab`() {
        val target = resolver { "" }.parseFocusTarget("myproj · 2 · 3")
        assertEquals(HerdrWorkspaceResolver.HerdrFocusTarget(2, "3"), target)
    }

    @Test
    fun `parses workspace with custom-named tab`() {
        val target = resolver { "" }.parseFocusTarget("myproj · 2 · codex")
        assertEquals(HerdrWorkspaceResolver.HerdrFocusTarget(2, "codex"), target)
    }

    @Test
    fun `returns null for non-herdr bodies`() {
        val r = resolver { "" }
        assertNull(r.parseFocusTarget(null))
        assertNull(r.parseFocusTarget(""))
        assertNull(r.parseFocusTarget("SFTP Error"))
        assertNull(r.parseFocusTarget("Paste rejected"))
    }

    // ── focusFromBody ────────────────────────────────────────────────

    @Test
    fun `workspace-only body focuses workspace by number`() = runTest {
        val commands = mutableListOf<String>()
        val result = resolver { cmd -> commands += cmd; "" }.focusFromBody("myproj · 2")

        assertTrue(result)
        assertEquals(1, commands.size)
        assertTrue(commands[0].endsWith("herdr workspace focus 2"))
        assertTrue(commands[0].startsWith("export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "))
    }

    @Test
    fun `unnamed tab body focuses tab positionally`() = runTest {
        val commands = mutableListOf<String>()
        val result = resolver { cmd -> commands += cmd; "" }.focusFromBody("myproj · 2 · 3")

        assertTrue(result)
        assertEquals(1, commands.size)
        assertTrue(commands[0].endsWith("herdr tab focus t_2_3"))
    }

    @Test
    fun `custom-named tab resolves tab id via tab list`() = runTest {
        val commands = mutableListOf<String>()
        val tabListJson =
            """{"id":"cli:tab:list","result":{"type":"tab_list","tabs":""" +
                """[{"tab_id":"w0:t1","workspace_id":"w0","number":1,"label":"1","focused":true},""" +
                """{"tab_id":"w0:t2","workspace_id":"w0","number":2,"label":"codex","focused":false}]}}"""
        val result = resolver { cmd ->
            commands += cmd
            if (cmd.contains("herdr tab list")) tabListJson else ""
        }.focusFromBody("myproj · 2 · codex")

        assertTrue(result)
        assertEquals(2, commands.size)
        assertTrue(commands[0].endsWith("herdr tab list --workspace 2"))
        assertTrue(commands[1].endsWith("herdr tab focus w0:t2"))
    }

    @Test
    fun `custom-named tab missing from list falls back to workspace focus`() = runTest {
        val commands = mutableListOf<String>()
        val tabListJson =
            """{"id":"cli:tab:list","result":{"type":"tab_list","tabs":""" +
                """[{"tab_id":"w0:t1","workspace_id":"w0","number":1,"label":"1","focused":true}]}}"""
        val result = resolver { cmd ->
            commands += cmd
            if (cmd.contains("herdr tab list")) tabListJson else ""
        }.focusFromBody("myproj · 2 · unknown-tab")

        assertTrue(result)
        assertEquals(2, commands.size)
        assertTrue(commands[1].endsWith("herdr workspace focus 2"))
    }

    @Test
    fun `non-herdr body issues no command`() = runTest {
        val commands = mutableListOf<String>()
        val result = resolver { cmd -> commands += cmd; "" }.focusFromBody("Paste rejected")

        assertFalse(result)
        assertTrue(commands.isEmpty())
    }
}

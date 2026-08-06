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

    // ── agent list and focus ──────────────────────────────────────────

    @Test
    fun `lists recognized agents`() = runTest {
        val commands = mutableListOf<String>()
        val output = buildString {
            append("""{"id":"cli:agent:list","result":{"type":"agent_list","agents":[""")
            append("""{"agent":"pi","name":"morphe-disk","agent_status":"working","cwd":"/work/piko","""")
            append("""workspace_id":"w0","tab_id":"w0:t1","pane_id":"w0:p2","terminal_id":"term0","""")
            append("""focused":true,"terminal_title_stripped":"pi - piko"}]}}""")
            append("\n")
            append("""{"id":"cli:workspace:list","result":{"type":"workspace_list","workspaces":[""")
            append("""{"workspace_id":"w0","label":"piko","focused":true}]}}""")
        }

        val agents = resolver { cmd -> commands += cmd; output }.listAgents()

        assertEquals(1, commands.size)
        assertTrue(commands[0].endsWith("herdr agent list; herdr workspace list"))
        assertEquals(1, agents.size)
        assertEquals("pi", agents[0].agent)
        assertEquals("morphe-disk", agents[0].name)
        assertEquals("working", agents[0].agentStatus)
        assertEquals("w0:p2", agents[0].paneId)
        assertEquals("piko", agents[0].workspaceLabel)
        assertTrue(agents[0].focused)
        assertEquals("pi - piko", agents[0].terminalTitle)
    }

    @Test
    fun `ignores malformed and non-agent list responses`() {
        val resolver = resolver {
            "not json\n{\"id\":\"cli:agent:list\",\"result\":{\"type\":\"error\"}}"
        }

        assertTrue(resolver.parseAgentList("not json").isEmpty())
        assertTrue(resolver.parseAgentList("[]").isEmpty())
        assertTrue(
            resolver.parseAgentList(
                "{\"id\":\"cli:agent:list\",\"result\":{\"type\":\"error\"}}"
            ).isEmpty()
        )
    }

    @Test
    fun `focuses an agent pane`() = runTest {
        val commands = mutableListOf<String>()
        val agent = HerdrWorkspaceResolver.HerdrAgentInfo(
            agent = "pi",
            name = null,
            agentStatus = "working",
            cwd = "/work",
            workspaceId = "w0",
            tabId = "w0:t1",
            paneId = "w0:p2",
            terminalId = "term0",
            focused = false,
            terminalTitle = null,
        )
        val output =
            """{"id":"cli:agent:focus","result":{"type":"agent_info","agent":{"pane_id":"w0:p2"}}}"""

        val result = resolver { cmd -> commands += cmd; output }.focusAgent(agent)

        assertTrue(result)
        assertEquals(1, commands.size)
        assertTrue(commands[0].endsWith("herdr agent focus 'w0:p2'"))
    }

    @Test
    fun `reports failed agent focus`() = runTest {
        val agent = HerdrWorkspaceResolver.HerdrAgentInfo(
            agent = "pi",
            name = null,
            agentStatus = "working",
            cwd = null,
            workspaceId = "w0",
            tabId = "w0:t1",
            paneId = "w0:p2",
            terminalId = "term0",
            focused = false,
            terminalTitle = null,
        )

        assertFalse(
            resolver { "{\"id\":\"cli:agent:focus\",\"error\":{\"message\":\"missing\"}}" }
                .focusAgent(agent)
        )
    }
}

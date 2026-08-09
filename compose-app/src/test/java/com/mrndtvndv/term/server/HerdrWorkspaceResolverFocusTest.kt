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

    // ── process-info and pane titles ──────────────────────────────────

    private val paneListOutput = buildString {
        append("""{"id":"cli:workspace:list","result":{"type":"workspace_list","workspaces":[""")
        append("""{"workspace_id":"w0","label":"proj","focused":true}]}}""")
        append("\n")
        append("""{"id":"cli:pane:list","result":{"type":"pane_list","panes":[""")
        append("""{"pane_id":"w0:p1","tab_id":"w0:t1","workspace_id":"w0",""")
        append(""""terminal_title":"nvim /work/proj","terminal_title_stripped":"nvim /work/proj",""")
        append(""""focused":true,"cwd":"/work/proj"}""")
        append(",")
        append("""{"pane_id":"w0:p2","tab_id":"w0:t1","workspace_id":"w0","agent":"pi",""")
        append(""""terminal_title":"π - proj","terminal_title_stripped":"π - proj",""")
        append(""""agent_status":"working","focused":false,"cwd":"/work/proj"}]}}""")
    }

    private val processInfo = buildString {
        append("""{"id":"cli:pane:process_info","result":{"type":"pane_process_info",""")
        append(""""process_info":{"shell_pid":1,"pane_id":"w0:p1",""")
        append(""""foreground_processes":[{"pid":2,"argv0":"nvim","name":"nvim",""")
        append(""""cmdline":"nvim /work/proj"}]}}}""")
    }

    @Test
    fun `queries process info for idle panes and replaces titles`() = runTest {
        val commands = mutableListOf<String>()
        val workspaces = resolver { cmd ->
            commands += cmd
            if (cmd.contains("process-info")) processInfo else paneListOutput
        }.listWorkspaceTabs()

        assertEquals(2, commands.size)
        assertTrue(commands[1].contains("herdr pane process-info --pane 'w0:p1'"))
        val idlePane = workspaces[0].tabs[0].panes.first { it.paneId == "w0:p1" }
        assertEquals("nvim", idlePane.processName)
        assertEquals("nvim", idlePane.title)
        val agentPane = workspaces[0].tabs[0].panes.first { it.paneId == "w0:p2" }
        assertEquals("π - proj", agentPane.title)
        assertNull(agentPane.processName)
    }

    @Test
    fun `skips process info when every pane runs an agent`() = runTest {
        val commands = mutableListOf<String>()
        val allAgents = paneListOutput.replaceFirst(
            "\"terminal_title\":\"nvim /work/proj\"",
            "\"agent\":\"pi\",\"agent_status\":\"idle\",\"terminal_title\":\"pi - proj\"",
        )
        resolver { cmd -> commands += cmd; allAgents }.listWorkspaceTabs()

        assertEquals(1, commands.size)
    }

    @Test
    fun `keeps original title when process info is unavailable`() = runTest {
        val commands = mutableListOf<String>()
        val workspaces = resolver { cmd ->
            commands += cmd
            if (cmd.contains("process-info")) "" else paneListOutput
        }.listWorkspaceTabs()

        val idlePane = workspaces[0].tabs[0].panes.first { it.paneId == "w0:p1" }
        assertEquals("nvim /work/proj", idlePane.title)
        assertNull(idlePane.processName)
    }

    @Test
    fun `resolves the agent session title from session metadata`() = runTest {
        val output = paneListOutput.replaceFirst(
            "\"agent\":\"pi\",",
            "\"agent\":\"pi\",\"agent_session\":{" +
                "\"agent\":\"pi\",\"kind\":\"path\",\"source\":\"herdr:pi\"," +
                "\"value\":\"/Users/test/.pi/sessions/session-123.jsonl\"},",
        )
        val workspaces = resolver { cmd ->
            when {
                cmd.contains("process-info") -> processInfo
                cmd.contains("session_info") -> "w0:p2\tsession-title\n"
                else -> output
            }
        }.listWorkspaceTabs()

        val tab = workspaces.single().tabs.single()
        val agentPane = tab.panes.first { it.agent == "pi" }
        assertEquals(
            "/Users/test/.pi/sessions/session-123.jsonl",
            agentPane.agentSession?.value,
        )
        assertEquals("path", agentPane.agentSession?.kind)
        assertEquals("session-title", agentPane.agentSessionName)
        assertEquals("session-title", tab.agentSessionName)
    }

    @Test
    fun `resolves codex thread name from the session index`() = runTest {
        val output = paneListOutput.replaceFirst(
            "\"agent\":\"pi\",",
            "\"agent\":\"codex\",\"agent_session\":{" +
                "\"agent\":\"codex\",\"kind\":\"id\",\"source\":\"herdr:codex\"," +
                "\"value\":\"thread-123\"},",
        )
        val workspaces = resolver { cmd ->
            when {
                cmd.contains("process-info") -> processInfo
                cmd.contains("session_index.jsonl") -> "w0:p2\tcodex-title\n"
                else -> output
            }
        }.listWorkspaceTabs()

        val agentPane = workspaces.single().tabs.single().panes.first { it.agent == "codex" }
        assertEquals("codex-title", agentPane.agentSessionName)
    }

    @Test
    fun `resolves hermes session title from the state database`() = runTest {
        val output = paneListOutput.replaceFirst(
            "\"agent\":\"pi\",",
            "\"agent\":\"hermes\",\"agent_session\":{" +
                "\"agent\":\"hermes\",\"kind\":\"id\",\"source\":\"herdr:hermes\"," +
                "\"value\":\"session-123\"},",
        )
        val workspaces = resolver { cmd ->
            when {
                cmd.contains("process-info") -> processInfo
                cmd.contains(".hermes/state.db") -> "w0:p2\thermes-title\n"
                else -> output
            }
        }.listWorkspaceTabs()

        val agentPane = workspaces.single().tabs.single().panes.first { it.agent == "hermes" }
        assertEquals("hermes-title", agentPane.agentSessionName)
    }
}

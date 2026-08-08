package com.mrndtvndv.term.server

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parses Herdr workspace, pane, and agent command output.
 * Pure logic — no Android dependencies, testable with sample JSON.
 */
@Suppress("TooManyFunctions")
class HerdrWorkspaceResolver(
    private val execCommand: suspend (String) -> String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * @param host Host to build workspace key
     * @param username Username to build workspace key
     * @return Parsed workspace info, or null if herdr is not available
     */
    suspend fun resolve(
        host: String,
        username: String,
    ): WorkspaceInfo? {
        val output = execCommand(
            "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; " +
                "herdr workspace list; herdr pane list"
        )
        return parseHerdrOutput(output, host, username)
    }

    internal fun parseHerdrOutput(
        output: String,
        host: String,
        username: String,
    ): WorkspaceInfo? {
        val workspaceResult = parseWorkspaceLines(output) ?: return null
        val workspaceKey = "${workspaceResult.label}_${host}_$username"

        val paneCwd = if (workspaceResult.focusedWsId != null) {
            workspaceResult.panes.firstOrNull {
                it["workspace_id"]?.jsonPrimitive?.contentOrNull == workspaceResult.focusedWsId &&
                    it["focused"]?.jsonPrimitive?.booleanOrNull == true
            }?.get("cwd")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }
        } else null

        return WorkspaceInfo(
            workspaceKey = workspaceKey,
            workspaceLabel = workspaceResult.label,
            cwd = paneCwd ?: "/",
        )
    }

    /**
     * Target parsed out of an OSC notification body (`<label> · <N>[ · <tab>]`).
     * @param workspaceNumber 1-based workspace number (matches `WorkspaceInfo.number`)
     * @param tabLabel Tab display name (custom name or 1-based tab number), when present
     */
    data class HerdrFocusTarget(
        val workspaceNumber: Int,
        val tabLabel: String? = null,
    )

    data class HerdrAgentInfo(
        val agent: String?,
        val name: String?,
        val agentStatus: String,
        val cwd: String?,
        val workspaceId: String,
        val tabId: String,
        val paneId: String,
        val terminalId: String,
        val focused: Boolean,
        val terminalTitle: String?,
        val workspaceLabel: String? = null,
    )

    data class HerdrPaneNode(
        val paneId: String,
        val tabId: String,
        val workspaceId: String,
        val title: String,
        val agent: String?,
        val agentStatus: String,
        val focused: Boolean,
        /** Foreground process name (`argv0`) when herdr reports it; null otherwise. */
        val processName: String? = null,
    )

    data class HerdrTabNode(
        val tabId: String,
        val title: String,
        val agent: String?,
        val agentStatus: String,
        val focused: Boolean,
        val workspaceId: String,
        val paneId: String,
        val panes: List<HerdrPaneNode> = emptyList(),
    )

    data class HerdrWorkspaceNode(
        val workspaceId: String,
        val label: String,
        val tabs: List<HerdrTabNode>,
    )

    /**
     * Parse a herdr notification body into a focus target.
     * Returns null when the body is not a herdr workspace context string.
     */
    fun parseFocusTarget(body: String?): HerdrFocusTarget? {
        if (body.isNullOrBlank()) return null
        val parts = body.split(" · ")
        val number = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val tabLabel = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        return HerdrFocusTarget(number, tabLabel)
    }

    /**
     * Focus the workspace (and tab, when identifiable) named in a notification body.
     * `herdr workspace focus` and `herdr tab focus` accept the same 1-based numbers
     * that appear in the OSC body, so no list lookup is needed for the common cases;
     * a custom-named tab falls back to `herdr tab list` to resolve its id.
     *
     * @return true when a focus command was issued
     */
    suspend fun focusFromBody(body: String?): Boolean {
        val target = parseFocusTarget(body) ?: return false
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        execCommand(prefix + buildFocusCommand(target, prefix))
        return true
    }

    suspend fun listAgents(): List<HerdrAgentInfo> {
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        return parseAgentList(execCommand(prefix + "herdr agent list; herdr workspace list"))
    }

    suspend fun listWorkspaceTabs(): List<HerdrWorkspaceNode> {
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        val workspaces = parseWorkspaceTabs(
            execCommand(prefix + "herdr workspace list; herdr pane list")
        )
        val idlePaneIds = workspaces.asSequence()
            .flatMap { it.tabs }
            .flatMap { it.panes }
            .filter { it.agent == null }
            .map { it.paneId }
            .toList()
        if (idlePaneIds.isEmpty()) return workspaces
        val processQuery = prefix + idlePaneIds.joinToString("; ") { paneId ->
            "herdr pane process-info --pane ${shellQuote(paneId)}"
        }
        return attachProcessNames(workspaces, parseProcessNames(execCommand(processQuery)))
    }

    suspend fun focusAgent(agent: HerdrAgentInfo): Boolean = focusAgentPane(agent.paneId)

    suspend fun focusTab(tab: HerdrTabNode): Boolean =
        if (tab.agent != null) focusAgentPane(tab.paneId) else focusTabId(tab.tabId)

    suspend fun focusPane(pane: HerdrPaneNode): Boolean =
        if (pane.agent != null) focusAgentPane(pane.paneId) else focusTabId(pane.tabId)

    /**
     * Close a pane's terminal. Herdr kills whatever runs inside the pane.
     * @return true when the close command was issued
     */
    suspend fun closePane(paneId: String): Boolean {
        val validPaneId = paneId.takeIf { it.isNotBlank() } ?: return false
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        execCommand(prefix + "herdr pane close ${shellQuote(validPaneId)}")
        return true
    }

    private suspend fun focusTabId(tabId: String): Boolean {
        val validTabId = tabId.takeIf { it.isNotBlank() } ?: return false
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        execCommand(prefix + "herdr tab focus $validTabId")
        return true
    }

    private suspend fun focusAgentPane(paneId: String): Boolean {
        val validPaneId = paneId.takeIf { it.isNotBlank() } ?: return false
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        val output = execCommand(prefix + "herdr agent focus ${shellQuote(validPaneId)}")
        return output.lineSequence().mapNotNull { parseHerdrLine(it) }.any { (id, result) ->
            if (id != "cli:agent:focus") return@any false
            if (result["type"]?.jsonPrimitive?.contentOrNull != "agent_info") return@any false
            val focusedPaneId = runCatching {
                result["agent"]?.jsonObject?.get("pane_id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            focusedPaneId == validPaneId
        }
    }

    private suspend fun buildFocusCommand(target: HerdrFocusTarget, prefix: String): String {
        val tabLabel = target.tabLabel
        if (tabLabel == null) {
            return "herdr workspace focus ${target.workspaceNumber}"
        }
        val tabNumber = tabLabel.toIntOrNull()
        if (tabNumber != null) {
            // Unnamed tab: t_<ws>_<tab> is positional and accepts the numbers from the body.
            return "herdr tab focus t_${target.workspaceNumber}_$tabNumber"
        }
        // Custom-named tab: resolve tab id via tab list.
        val output = execCommand(prefix + "herdr tab list --workspace ${target.workspaceNumber}")
        val tabId = findTabIdByLabel(output, tabLabel)
        return tabId?.let { "herdr tab focus $it" }
            ?: "herdr workspace focus ${target.workspaceNumber}"
    }

    private fun findTabIdByLabel(output: String, label: String): String? {
        output.lines().forEach { line ->
            val parsed = parseHerdrLine(line) ?: return@forEach
            if (parsed.first != "cli:tab:list") return@forEach
            val tabArray = parsed.second["tabs"]?.jsonArray ?: return@forEach
            for (tab in tabArray) {
                val tabObj = tab.jsonObject
                if (tabObj["label"]?.jsonPrimitive?.contentOrNull == label) {
                    return tabObj["tab_id"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    internal fun parseAgentList(output: String): List<HerdrAgentInfo> {
        val workspaceLabels = parseWorkspaceLabels(output)
        for (line in output.lineSequence()) {
            val parsed = parseHerdrLine(line) ?: continue
            if (parsed.first == "cli:agent:list" &&
                parsed.second["type"]?.jsonPrimitive?.contentOrNull == "agent_list"
            ) {
                return parseAgents(parsed.second).map { agent ->
                    agent.copy(workspaceLabel = workspaceLabels[agent.workspaceId])
                }
            }
        }
        return emptyList()
    }

    private fun parseAgents(result: JsonObject): List<HerdrAgentInfo> =
        runCatching { result["agents"]?.jsonArray }.getOrNull().orEmpty().mapNotNull { element ->
            val agent = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
            val paneId = agent["pane_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val agentStatus = agent["agent_status"]?.jsonPrimitive?.contentOrNull
                ?: return@mapNotNull null
            val workspaceId = agent["workspace_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val tabId = agent["tab_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val terminalId = agent["terminal_id"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val focused = agent["focused"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
            HerdrAgentInfo(
                agent = agent["agent"]?.jsonPrimitive?.contentOrNull,
                name = agent["name"]?.jsonPrimitive?.contentOrNull,
                agentStatus = agentStatus,
                cwd = agent["cwd"]?.jsonPrimitive?.contentOrNull,
                workspaceId = workspaceId,
                tabId = tabId,
                paneId = paneId,
                terminalId = terminalId,
                focused = focused,
                terminalTitle = agent["terminal_title_stripped"]?.jsonPrimitive?.contentOrNull
                    ?: agent["terminal_title"]?.jsonPrimitive?.contentOrNull,
            )
        }

    /** Maps pane id to the leading foreground process name (`argv0`, falling back to `name`). */
    internal fun parseProcessNames(output: String): Map<String, String> = buildMap {
        output.lineSequence()
            .mapNotNull(::parseHerdrLine)
            .filter { (id, _) -> id == "cli:pane:process_info" }
            .mapNotNull { (_, result) -> parseProcessName(result) }
            .forEach { (paneId, processName) -> put(paneId, processName) }
    }

    private fun parseProcessName(result: JsonObject): Pair<String, String>? {
        val processInfo = runCatching {
            result["process_info"]?.jsonObject
        }.getOrNull() ?: return null
        val paneId = jsonContent(processInfo, "pane_id")?.takeIf { it.isNotBlank() }
            ?: jsonContent(result, "pane_id")?.takeIf { it.isNotBlank() }
        val firstProcess = runCatching {
            processInfo["foreground_processes"]?.jsonArray
        }.getOrNull().orEmpty().firstOrNull()
            ?.let { element -> runCatching { element.jsonObject }.getOrNull() }
        val processName = firstProcess?.let { process ->
            jsonContent(process, "argv0")?.takeIf { it.isNotBlank() }
                ?: jsonContent(process, "name")?.takeIf { it.isNotBlank() }
        }
        if (paneId == null || processName == null) return null
        return paneId to processName
    }

    /** Replaces idle pane titles with their process name (e.g. `nvim /path` → `nvim`). */
    internal fun attachProcessNames(
        workspaces: List<HerdrWorkspaceNode>,
        processNames: Map<String, String>,
    ): List<HerdrWorkspaceNode> {
        if (processNames.isEmpty()) return workspaces
        return workspaces.map { workspace ->
            workspace.copy(
                tabs = workspace.tabs.map { tab ->
                    tab.copy(
                        panes = tab.panes.map { pane ->
                            if (pane.agent != null) return@map pane
                            val processName = processNames[pane.paneId] ?: return@map pane
                            pane.copy(processName = processName, title = processName)
                        }
                    )
                }
            )
        }
    }

    private fun parseWorkspaceTabs(output: String): List<HerdrWorkspaceNode> {
        val workspaceLabels = parseWorkspaceLabels(output)
        val workspaces = parseWorkspaceEntries(output)
        if (workspaces.isEmpty()) return emptyList()

        val panes = parsePaneLines(output)
        return workspaces
            .sortedWith(
                compareBy<WorkspaceEntry> { it.number ?: Int.MAX_VALUE }
                    .thenBy { it.order },
            )
            .map { workspace ->
                HerdrWorkspaceNode(
                    workspaceId = workspace.workspaceId,
                    label = workspaceLabels[workspace.workspaceId] ?: workspace.workspaceId,
                    tabs = parseTabs(workspace.workspaceId, panes),
                )
            }
    }

    private fun parseWorkspaceEntries(output: String): List<WorkspaceEntry> =
        output.lineSequence()
            .mapNotNull(::parseHerdrLine)
            .filter { (id, _) -> id == "cli:workspace:list" }
            .flatMap { (_, result) ->
                val workspaceArray = runCatching {
                    result["workspaces"]?.jsonArray
                }.getOrNull()
                workspaceArray?.asSequence()?.mapNotNull { workspace ->
                    runCatching { workspace.jsonObject }.getOrNull()?.let { workspaceObject ->
                        jsonContent(workspaceObject, "workspace_id")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { workspaceId ->
                                WorkspaceEntry(
                                    workspaceId = workspaceId,
                                    number = jsonContent(workspaceObject, "number")?.toIntOrNull(),
                                    order = 0,
                                )
                            }
                    }
                } ?: emptySequence()
            }
            .mapIndexed { index, workspace -> workspace.copy(order = index) }
            .toList()

    private fun parsePaneLines(output: String): List<JsonObject> {
        val panes = mutableListOf<JsonObject>()
        for (line in output.lineSequence()) {
            val parsed = parseHerdrLine(line) ?: continue
            if (parsed.first == "cli:pane:list") {
                parsePaneList(parsed.second, panes)
            }
        }
        return panes
    }

    private fun parseTabs(
        workspaceId: String,
        panes: List<JsonObject>,
    ): List<HerdrTabNode> {
        val panesByTab = panes
            .asSequence()
            .filter { jsonContent(it, "workspace_id") == workspaceId }
            .mapNotNull { pane ->
                val tabId = jsonContent(pane, "tab_id")?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                tabId to pane
            }
            .groupBy({ it.first }, { it.second })

        return panesByTab.values.mapNotNull(::parseTab)
    }

    @Suppress("ReturnCount")
    private fun parseTab(panes: List<JsonObject>): HerdrTabNode? {
        val firstPane = panes.firstOrNull() ?: return null
        val workspaceId = jsonContent(firstPane, "workspace_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        val tabId = jsonContent(firstPane, "tab_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        val agentPane = panes.firstOrNull { it.containsKey("agent") }
        val agent = agentPane?.let { pane ->
            jsonContent(pane, "agent")?.takeIf { it.isNotBlank() }
        }
        val paneId = agentPane
            ?.let { pane -> jsonContent(pane, "pane_id") }
            ?.takeIf { it.isNotBlank() }
            ?: panes.asSequence()
                .mapNotNull { pane -> jsonContent(pane, "pane_id") }
                .firstOrNull { it.isNotBlank() }
            ?: return null
        val agentStatus = panes
            .firstOrNull { it.containsKey("agent_status") }
            ?.let { pane -> jsonContent(pane, "agent_status") }
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        val paneNodes = panes.mapNotNull { pane -> parsePaneNode(pane, tabId) }

        return HerdrTabNode(
            tabId = tabId,
            title = tabTitle(firstPane, agent),
            agent = agent,
            agentStatus = agentStatus,
            focused = panes.any { pane -> jsonBoolean(pane, "focused") == true },
            workspaceId = workspaceId,
            paneId = paneId,
            panes = paneNodes,
        )
    }

    private fun parsePaneNode(
        pane: JsonObject,
        tabId: String,
    ): HerdrPaneNode? {
        val paneId = jsonContent(pane, "pane_id")?.takeIf { it.isNotBlank() } ?: return null
        val workspaceId = jsonContent(pane, "workspace_id")
            ?.takeIf { it.isNotBlank() } ?: return null
        val agent = pane.takeIf { it.containsKey("agent") }?.let {
            jsonContent(it, "agent")?.takeIf { value -> value.isNotBlank() }
        }
        val agentStatus = pane.takeIf { it.containsKey("agent_status") }
            ?.let { jsonContent(it, "agent_status") }
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"
        return HerdrPaneNode(
            paneId = paneId,
            tabId = tabId,
            workspaceId = workspaceId,
            title = tabTitle(pane, agent),
            agent = agent,
            agentStatus = agentStatus,
            focused = jsonBoolean(pane, "focused") == true,
        )
    }

    private fun tabTitle(
        firstPane: JsonObject,
        agent: String?,
    ): String {
        val cwdBasename = jsonContent(firstPane, "cwd")
            ?.trim()
            ?.trimEnd('/')
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return listOf(
            jsonContent(firstPane, "terminal_title"),
            jsonContent(firstPane, "terminal_title_stripped"),
            agent,
            cwdBasename,
        ).firstOrNull { !it.isNullOrBlank() } ?: "Terminal"
    }

    private fun jsonContent(element: JsonObject, key: String): String? =
        runCatching { element[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun jsonBoolean(element: JsonObject, key: String): Boolean? =
        runCatching { element[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()

    private data class WorkspaceEntry(
        val workspaceId: String,
        val number: Int?,
        val order: Int,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private data class WorkspaceLines(
        val focusedWsId: String?,
        val label: String,
        val panes: List<JsonObject>,
    )

    private fun parseWorkspaceLines(output: String): WorkspaceLines? {
        var focusedWsId: String? = null
        var wsLabel: String? = null
        val panes = mutableListOf<JsonObject>()

        output.lines().forEach { line ->
            val parsed = parseHerdrLine(line)
            if (parsed == null) return@forEach
            if (parsed.first == "cli:workspace:list") {
                parseWorkspaceList(parsed.second)?.let { (wsId, label) ->
                    focusedWsId = wsId
                    wsLabel = label
                }
            } else if (parsed.first == "cli:pane:list") {
                parsePaneList(parsed.second, panes)
            }
        }

        val label = wsLabel ?: focusedWsId ?: return null
        return WorkspaceLines(focusedWsId = focusedWsId, label = label, panes = panes)
    }

    private fun parseHerdrLine(line: String): Pair<String, JsonObject>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val jsonObject = json.parseToJsonElement(trimmed).jsonObject
            val id = jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: ""
            val result = jsonObject["result"]?.jsonObject ?: return null
            Pair(id, result)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    private fun parseWorkspaceLabels(output: String): Map<String, String> = buildMap {
        output.lineSequence()
            .mapNotNull(::parseHerdrLine)
            .filter { (id, _) -> id == "cli:workspace:list" }
            .flatMap { (_, result) ->
                result["workspaces"]?.jsonArray?.asSequence() ?: emptySequence()
            }
            .mapNotNull { workspace ->
                val workspaceObject = workspace.jsonObject
                val workspaceId = workspaceObject["workspace_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                val label = workspaceObject["label"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
                if (workspaceId != null && label != null) workspaceId to label else null
            }
            .forEach { (workspaceId, label) -> put(workspaceId, label) }
    }

    private fun parseWorkspaceList(
        result: JsonObject
    ): Pair<String?, String?>? {
        val wsArray = result["workspaces"]?.jsonArray ?: return null
        for (ws in wsArray) {
            val wsObj = ws.jsonObject
            if (wsObj["focused"]?.jsonPrimitive?.booleanOrNull == true) {
                val wsId = wsObj["workspace_id"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                val label = wsObj["label"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotEmpty() }
                return Pair(wsId, label)
            }
        }
        return null
    }

    private fun parsePaneList(
        result: JsonObject,
        panes: MutableList<JsonObject>,
    ) {
        val paneArray = result["panes"]?.jsonArray ?: return
        for (pane in paneArray) {
            panes.add(pane.jsonObject)
        }
    }

    data class WorkspaceInfo(
        val workspaceKey: String,
        val workspaceLabel: String,
        val cwd: String,
    )
}

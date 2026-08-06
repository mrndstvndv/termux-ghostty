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

    suspend fun focusAgent(agent: HerdrAgentInfo): Boolean {
        val paneId = agent.paneId.takeIf { it.isNotBlank() } ?: return false
        val prefix = "export PATH=\$PATH:/opt/homebrew/bin:/usr/local/bin; "
        val output = execCommand(prefix + "herdr agent focus ${shellQuote(paneId)}")
        return output.lineSequence().mapNotNull { parseHerdrLine(it) }.any { (id, result) ->
            if (id != "cli:agent:focus") return@any false
            if (result["type"]?.jsonPrimitive?.contentOrNull != "agent_info") return@any false
            val focusedPaneId = runCatching {
                result["agent"]?.jsonObject?.get("pane_id")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            focusedPaneId == paneId
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

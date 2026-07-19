package com.mrndtvndv.term.server

import org.json.JSONObject

/**
 * Parses the output of `herdr workspace list; herdr pane list`.
 * Pure logic — no Android dependencies, testable with sample JSON.
 */
class HerdrWorkspaceResolver(
    private val execCommand: suspend (String) -> String,
) {
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
                it.optString("workspace_id") == workspaceResult.focusedWsId &&
                    it.optBoolean("focused", false)
            }?.optString("cwd")?.takeIf { it.isNotEmpty() }
        } else null

        return WorkspaceInfo(
            workspaceKey = workspaceKey,
            workspaceLabel = workspaceResult.label,
            cwd = paneCwd ?: "/",
        )
    }

    private data class WorkspaceLines(
        val focusedWsId: String?,
        val label: String,
        val panes: List<JSONObject>,
    )

    private fun parseWorkspaceLines(output: String): WorkspaceLines? {
        var focusedWsId: String? = null
        var wsLabel: String? = null
        val panes = mutableListOf<JSONObject>()

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

    private fun parseHerdrLine(line: String): Pair<String, JSONObject>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val json = JSONObject(trimmed)
            val id = json.optString("id")
            val result = json.optJSONObject("result") ?: return null
            Pair(id, result)
        } catch (_: org.json.JSONException) {
            null
        }
    }

    private fun parseWorkspaceList(result: JSONObject): Pair<String?, String?>? {
        val wsArray = result.optJSONArray("workspaces") ?: return null
        for (i in 0 until wsArray.length()) {
            val ws = wsArray.optJSONObject(i) ?: continue
            if (ws.optBoolean("focused", false)) {
                val wsId = ws.optString("workspace_id").takeIf { it.isNotEmpty() }
                val label = ws.optString("label").takeIf { it.isNotEmpty() }
                return Pair(wsId, label)
            }
        }
        return null
    }

    private fun parsePaneList(result: JSONObject, panes: MutableList<JSONObject>) {
        val paneArray = result.optJSONArray("panes") ?: return
        for (i in 0 until paneArray.length()) {
            val pane = paneArray.optJSONObject(i) ?: continue
            panes.add(pane)
        }
    }

    data class WorkspaceInfo(
        val workspaceKey: String,
        val workspaceLabel: String,
        val cwd: String,
    )
}

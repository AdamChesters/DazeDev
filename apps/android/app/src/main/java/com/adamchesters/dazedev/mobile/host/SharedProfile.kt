package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject

class SharedProfile(
    document: JSONObject,
) {
    companion object {
        const val LOCAL_OWNER = "@device-workspaces"

        fun empty() =
            SharedProfile(
                JSONObject()
                    .put("schema", 1)
                    .put("revision", 0)
                    .put("preferences", JSONObject())
                    .put("workspaces", JSONArray())
                    .put("discovery", JSONObject()),
            )
    }

    val document = JSONObject(document.toString())
    val revision: Int get() = document.getInt("revision")

    init {
        require(document.getInt("schema") == 1) { "Unsupported workspace schema. Existing host data is preserved." }
        require(revision >= 0 && document.optJSONArray("workspaces") != null && document.optJSONObject("preferences") != null) { "Invalid host profile. Existing host data is preserved." }
        folders()
    }

    fun rebindOwner(
        hostId: String,
        fingerprint: String,
        details: HostReference? = null,
    ) {
        val workspaces = document.getJSONArray("workspaces")
        for (i in 0 until workspaces.length()) {
            val w = workspaces.getJSONObject(i)
            val old = w.getString("hostId")
            w.put("hostId", hostId)
            val panes = w.getJSONArray("panes")
            for (j in 0 until panes.length()) {
                val p = panes.getJSONObject(j)
                if (p.optString("hostKey") == fingerprint || (p.optString("hostKey").isBlank() && p.optString("hostId") == old)) {
                    p.put("hostId", hostId).put("hostKey", fingerprint)
                    if (details != null) {
                        val existing = p.optJSONObject("hostDetails") ?: JSONObject()
                        val fields = details.toJson()
                        fields.keys().forEach { key -> existing.put(key, fields.get(key)) }
                        p.put("hostDetails", existing)
                    }
                }
            }
        }
    }

    /** Points a saved terminal at a new tmux session id and stamp. Returns false if the pane is not here. */
    fun rebindPaneSession(
        paneId: String,
        sessionId: String,
        stamp: String,
    ): Boolean {
        require(Regex("\\$[0-9]+").matches(sessionId) && stamp.isNotBlank()) { "Invalid tmux session." }
        val workspaces = document.getJSONArray("workspaces")
        var changed = false
        for (i in 0 until workspaces.length()) {
            val panes = workspaces.getJSONObject(i).getJSONArray("panes")
            for (j in 0 until panes.length()) {
                val pane = panes.getJSONObject(j)
                if (pane.optString("id") == paneId) {
                    pane.put("sessionId", sessionId).put("stamp", stamp)
                    changed = true
                }
            }
        }
        return changed
    }

    fun folders(): List<WorkspaceFolder> {
        val w = document.getJSONArray("workspaces")
        return (0 until w.length()).map { i ->
            val folder = w.getJSONObject(i)
            val panes = folder.getJSONArray("panes")
            val records = (0 until panes.length()).map { panes.getJSONObject(it) }
            val byId = records.associateBy { it.getString("id") }
            val order = mutableListOf<String>()

            fun walk(
                layout: JSONObject,
                depth: Int,
            ) {
                require(depth <= 64) { "Workspace layout too deep" }
                when (layout.optString("kind")) {
                    "pane" -> {
                        order.add(layout.getString("id"))
                    }

                    "split" -> {
                        walk(layout.getJSONObject("first"), depth + 1)
                        walk(layout.getJSONObject("second"), depth + 1)
                    }
                }
            }
            folder.optJSONObject("layout")?.let { walk(it, 0) }
            order.addAll(records.map { it.getString("id") }.filterNot { it in order })
            val tabs = folder.optJSONArray("tabGroups") ?: JSONArray()
            val used = mutableSetOf<String>()
            val groupIds = mutableSetOf<String>()
            val groups =
                (0 until tabs.length()).mapNotNull { index ->
                    val group = tabs.optJSONObject(index) ?: return@mapNotNull null
                    val id = group.optString("id")
                    if (id.isBlank() || id in groupIds) return@mapNotNull null
                    val members = group.optJSONArray("panes") ?: return@mapNotNull null
                    val valid = (0 until members.length()).map { members.optString(it) }.distinct().filter { it in byId && it !in used }
                    if (valid.size < 2) return@mapNotNull null
                    groupIds.add(id)
                    used.addAll(valid)
                    TerminalTabGroup(id, valid)
                }
            WorkspaceFolder(
                folder.getString("id"),
                folder.optString("name", "Workspace"),
                order.distinct().mapNotNull { id ->
                    byId[id]?.let { p -> TerminalRow(id, p.optString("name", "Terminal"), p.optString("sessionId"), p.optString("stamp"), p.optString("hostId"), p.optString("hostKey"), p.optString("directory"), p.optJSONObject("hostDetails")?.let(HostReference::fromJson), p.optBoolean("shell") && p.optString("sessionId").isBlank()) }
                },
                folder.optJSONObject("colors")?.optString("background").orEmpty(),
                groups,
            )
        }
    }

    internal fun workspace(id: String): JSONObject {
        val rows = document.getJSONArray("workspaces")
        return (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("id") == id } ?: error("Workspace no longer exists.")
    }

    fun addSession(
        hostId: String,
        fingerprint: String,
        session: JSONObject,
        directory: String,
        workspaceId: String = "",
        workspaceName: String = session.getString("name"),
    ): String {
        val pid =
            java.util.UUID
                .randomUUID()
                .toString()
        val pane =
            JSONObject()
                .put("id", pid)
                .put("hostId", hostId)
                .put("hostKey", fingerprint)
                .put("sessionId", session.getString("id"))
                .put("stamp", session.getString("stamp"))
                .put("name", session.getString("name"))
                .put("directory", directory)
                .put("shell", session.optString("agent", "shell") == "shell")
                .put("repo", "")
                .put("identity", session.optString("identity"))
                .put("agent", session.optString("agent", "shell"))
                .put("model", session.optString("model"))
                .put("effort", session.optString("effort"))
                .put("operation", session.optString("operation"))
        val workspaces = document.getJSONArray("workspaces")
        if (workspaceId.isNotBlank()) {
            val existing =
                (0 until workspaces.length()).map { workspaces.getJSONObject(it) }.firstOrNull { it.getString("id") == workspaceId }
                    ?: error("Workspace no longer exists. Reload before adding a terminal.")
            existing.getJSONArray("panes").put(pane)
            val previous = existing.getJSONObject("layout")
            existing.put(
                "layout",
                JSONObject()
                    .put("kind", "split")
                    .put(
                        "id",
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ).put("axis", "row")
                    .put("ratio", 0.5)
                    .put("first", previous)
                    .put("second", JSONObject().put("kind", "pane").put("id", pid)),
            )
        } else {
            workspaces.put(
                JSONObject()
                    .put(
                        "id",
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    ).put("hostId", hostId)
                    .put("name", workspaceName)
                    .put("panes", JSONArray().put(pane))
                    .put("layout", JSONObject().put("kind", "pane").put("id", pid))
                    .put("focused", pid),
            )
        }
        return if (workspaceId.isNotBlank()) workspaceId else workspaces.getJSONObject(workspaces.length() - 1).getString("id")
    }
}

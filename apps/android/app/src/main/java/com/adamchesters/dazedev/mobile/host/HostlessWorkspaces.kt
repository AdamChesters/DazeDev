package com.adamchesters.dazedev.mobile.host

import org.json.JSONArray
import org.json.JSONObject

// Hostless (device) workspaces: create, fill from host terminals, tab groups and edits.

fun SharedProfile.createLocalWorkspace(name: String): String {
    require(name.isNotBlank())
    val id =
        java.util.UUID
            .randomUUID()
            .toString()
    document.getJSONArray("workspaces").put(
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("hostId", SharedProfile.LOCAL_OWNER)
            .put("panes", JSONArray())
            .put("layout", JSONObject().put("kind", "pane").put("id", ""))
            .put("focused", ""),
    )
    return id
}

private fun SharedProfile.balancedLayout(ids: List<String>): JSONObject {
    if (ids.size <= 1) return JSONObject().put("kind", "pane").put("id", ids.firstOrNull().orEmpty())
    val middle = ids.size / 2
    return JSONObject()
        .put("kind", "split")
        .put(
            "id",
            java.util.UUID
                .randomUUID()
                .toString(),
        ).put("axis", "row")
        .put("ratio", 0.5)
        .put("first", balancedLayout(ids.take(middle)))
        .put("second", balancedLayout(ids.drop(middle)))
}

fun SharedProfile.clonePane(
    destination: String,
    source: SharedProfile,
    workspaceId: String,
    paneId: String,
) {
    val target = workspace(destination)
    require(target.getString("hostId") == SharedProfile.LOCAL_OWNER) { "Choose a hostless workspace." }
    val originals = source.workspace(workspaceId).getJSONArray("panes")
    val original = (0 until originals.length()).map { originals.getJSONObject(it) }.firstOrNull { it.getString("id") == paneId } ?: error("Source terminal no longer exists.")
    require(listOf("sessionId", "stamp", "hostKey").all { original.optString(it).isNotBlank() }) { "Only persistent terminals with verified host identities can be added." }
    val id =
        java.util.UUID
            .randomUUID()
            .toString()
    val copy =
        JSONObject(original.toString()).put("id", id).put(
            "operation",
            java.util.UUID
                .randomUUID()
                .toString(),
        )
    val panes = target.getJSONArray("panes")
    val leaf = JSONObject().put("kind", "pane").put("id", id)
    target.put(
        "layout",
        if (panes.length() == 0) {
            leaf
        } else {
            JSONObject()
                .put("kind", "split")
                .put(
                    "id",
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                ).put("axis", "row")
                .put("ratio", 0.5)
                .put("first", target.getJSONObject("layout"))
                .put("second", leaf)
        },
    )
    panes.put(copy)
    target.put("layout", balancedLayout((0 until panes.length()).map { panes.getJSONObject(it).getString("id") }))
    folders()
}

fun SharedProfile.groupTabs(
    workspaceId: String,
    paneIds: List<String>,
) {
    val folder = folders().first { it.id == workspaceId }
    val selected = paneIds.distinct()
    require(selected.size >= 2 && selected.all { id -> folder.rows.any { it.paneId == id } }) { "Select at least two terminals from this workspace." }
    val remaining = folder.tabGroups.map { it.copy(panes = it.panes.filterNot { id -> id in selected }) }.filter { it.panes.size > 1 }
    writeTabs(
        workspaceId,
        remaining +
            TerminalTabGroup(
                java.util.UUID
                    .randomUUID()
                    .toString(),
                selected,
            ),
    )
}

fun SharedProfile.removeLocalView(
    workspaceId: String,
    paneId: String,
) {
    val folder = workspace(workspaceId)
    require(folder.getString("hostId") == SharedProfile.LOCAL_OWNER) { "Choose a hostless workspace." }
    val panes = folder.getJSONArray("panes")
    val remaining = (0 until panes.length()).map { panes.getJSONObject(it) }.filterNot { it.getString("id") == paneId }
    require(remaining.size < panes.length()) { "Terminal view no longer exists." }

    fun remove(node: JSONObject): JSONObject? =
        when (node.optString("kind")) {
            "pane" -> {
                node.takeUnless { it.optString("id") == paneId }
            }

            "split" -> {
                val first = remove(node.getJSONObject("first"))
                val second = remove(node.getJSONObject("second"))
                when {
                    first == null -> second
                    second == null -> first
                    else -> node.put("first", first).put("second", second)
                }
            }

            else -> {
                node
            }
        }
    ungroupTab(workspaceId, paneId)
    folder.put("panes", JSONArray(remaining))
    folder.put("layout", remove(folder.getJSONObject("layout")) ?: JSONObject().put("kind", "pane").put("id", ""))
    if (folder.optString("focused") == paneId) folder.put("focused", remaining.firstOrNull()?.getString("id").orEmpty())
}

fun SharedProfile.ungroupTab(
    workspaceId: String,
    paneId: String,
) {
    writeTabs(
        workspaceId,
        folders()
            .first { it.id == workspaceId }
            .tabGroups
            .map { it.copy(panes = it.panes.filterNot { id -> id == paneId }) }
            .filter { it.panes.size > 1 },
    )
}

private fun SharedProfile.writeTabs(
    workspaceId: String,
    groups: List<TerminalTabGroup>,
) {
    val folder = workspace(workspaceId)
    val existing = folder.optJSONArray("tabGroups") ?: JSONArray()
    val originals = (0 until existing.length()).mapNotNull { existing.optJSONObject(it) }.associateBy { it.optString("id") }
    folder.put(
        "tabGroups",
        JSONArray(
            groups.map { group ->
                val record = originals[group.id]?.let { JSONObject(it.toString()) } ?: JSONObject()
                record.put("id", group.id).put("panes", JSONArray(group.panes))
            },
        ),
    )
}

fun SharedProfile.editWorkspace(
    id: String,
    name: String? = null,
    remove: Boolean = false,
    move: Int = 0,
    background: String? = null,
) {
    val array = document.getJSONArray("workspaces")
    val rows = (0 until array.length()).map { array.getJSONObject(it) }.toMutableList()
    val index = rows.indexOfFirst { it.getString("id") == id }
    require(index >= 0) { "Workspace no longer exists. Reload before editing." }
    val row = rows[index]
    if (name != null) {
        require(name.isNotBlank())
        row.put("name", name)
    }
    if (background != null) {
        require(background.matches(Regex("#[0-9a-fA-F]{6}")))
        val colors = row.optJSONObject("colors") ?: JSONObject()
        colors.put("background", background)
        if (!colors.has("foreground")) colors.put("foreground", "#edede8")
        row.put("colors", colors)
    }
    if (remove) {
        rows.removeAt(index)
    } else if (move != 0) {
        rows.removeAt(index)
        rows.add((index + move).coerceIn(0, rows.size), row)
    }
    document.put("workspaces", JSONArray(rows))
}

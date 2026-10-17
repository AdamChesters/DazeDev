package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import java.util.UUID

data class AgentCapability(
    val id: String,
    val version: String,
    val models: List<String>,
    val efforts: Map<String, List<String>>,
    val modelFlag: Boolean,
    val effortFlag: Boolean,
    /** Display name; hosts before 0.3.9 send only the ID. */
    val name: String = id,
    /** False when a new session cannot carry a starting brief. */
    val prompt: Boolean = true,
    /** Added by the user on the host. */
    val custom: Boolean = false,
)

data class SessionLaunch(
    val name: String,
    val path: String,
    val agent: String,
    val model: String = "",
    val effort: String = "",
    val identity: String = "",
    val brief: String = "",
    val workspaceId: String = "",
    val workspaceName: String = name,
    val count: Int = 1,
    val operation: String = UUID.randomUUID().toString(),
) {
    fun arguments(): JSONObject =
        JSONObject()
            .put("name", name)
            .put("path", path)
            .put("agent", agent)
            .put("model", model)
            .put("effort", effort)
            .put("identity", identity)
            .put("brief", brief)
            .put("operation", operation)
}

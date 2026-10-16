package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import org.json.JSONArray
import org.json.JSONObject

/** The screen, host, folder and terminal to show again after Android ends the app process. No secrets. */
fun DazeDevMobileUiState.navigationJson(): String =
    JSONObject()
        .put(
            "stack",
            JSONArray(
                backStack.map { screen ->
                    when (screen) {
                        Screen.Hosts -> JSONObject().put("kind", "hosts")
                        is Screen.Host -> JSONObject().put("kind", "host").put("id", screen.hostId)
                        is Screen.LocalGroup -> JSONObject().put("kind", "group").put("id", screen.groupId)
                        is Screen.Terminal -> JSONObject().put("kind", "terminal").put("host", screen.hostId).put("session", screen.sessionId)
                    }
                },
            ),
        ).put("host", profile.id)
        .put("folder", selectedFolder ?: "")
        .put("sessionName", selectedSessionName ?: "")
        .put("stamp", selectedStamp ?: "")
        .put("label", selectedTargetLabel)
        .put("pane", selectedPaneId ?: "")
        .toString()

/**
 * Restores [navigationJson] after process death. Screens for hosts or groups that no longer exist are
 * dropped. Connections do not survive, so a restored terminal waits for Reconnect.
 */
fun DazeDevMobileUiState.restoreNavigation(
    json: String,
    host: (String) -> ConnectionProfile?,
): DazeDevMobileUiState {
    val saved = runCatching { JSONObject(json) }.getOrNull() ?: return this
    val entries = saved.optJSONArray("stack") ?: return this
    val stack =
        (0 until entries.length()).mapNotNull { i ->
            val entry = entries.optJSONObject(i) ?: return@mapNotNull null
            when (entry.optString("kind")) {
                "hosts" -> {
                    Screen.Hosts
                }

                "host" -> {
                    entry.optString("id").takeIf { host(it) != null }?.let(Screen::Host)
                }

                "group" -> {
                    entry.optString("id").takeIf { id -> localGroups.any { it.id == id } }?.let(Screen::LocalGroup)
                }

                "terminal" -> {
                    val id = entry.optString("host")
                    val session = entry.optString("session")
                    if (host(id) != null && session.isNotEmpty()) Screen.Terminal(id, session) else null
                }

                else -> {
                    null
                }
            }
        }
    if (stack.isEmpty() || stack.first() != Screen.Hosts) return this
    val profile = host(saved.optString("host")) ?: profile
    val restored =
        copy(
            backStack = stack,
            profile = profile,
            selectedFolder = saved.optString("folder").ifEmpty { null },
            selectedPaneId = saved.optString("pane").ifEmpty { null },
        )
    val terminal = stack.last() as? Screen.Terminal ?: return restored
    return restored.copy(
        selectedSessionName = saved.optString("sessionName"),
        selectedSessionId = terminal.sessionId,
        selectedStamp = saved.optString("stamp"),
        selectedTargetHost = terminal.hostId,
        selectedTargetLabel = saved.optString("label"),
        terminalError = "Android closed DazeDev in the background. Reconnect to continue.",
    )
}

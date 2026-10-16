package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.SessionLaunch
import com.adamchesters.dazedev.mobile.host.SharedProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

/** tmux sessions on the selected host: list, start, rename, end and adopt. */
class SessionController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val store: ProfileStore,
    private val scope: CoroutineScope,
    private val terminal: TerminalController,
    private val workspace: WorkspaceController,
) {
    private fun current(): HostEntry? = registry.entry(ui.value.profile.id)

    fun refreshSessions() {
        val h = current() ?: return
        if (h.refreshing) return
        scope.launch {
            h.refreshing = true
            registry.publish()
            try {
                registry.refresh(h)
            } catch (e: Exception) {
                ui.message(e.message ?: "Refresh failed.")
            } finally {
                h.refreshing = false
                registry.publish()
            }
        }
    }

    fun reloadProfile() {
        val h = current() ?: return
        scope.launch {
            try {
                registry.reload(h)
            } catch (e: Exception) {
                ui.message(e.message ?: "Reload failed.")
            }
        }
    }

    fun selectSession(name: String) {
        val h = current() ?: return
        val s = h.sessions.firstOrNull { it.name == name } ?: return
        ui.update { it.copy(selectedPaneId = null) }
        terminal.openSession(h, s.id, s.stamp, s.name)
    }

    fun adoptSession(name: String) {
        val h = current() ?: return
        val s = h.sessions.firstOrNull { it.name == name } ?: return
        workspace.editShared("Could not save workspace. Your draft is retained.") { draft ->
            val session =
                JSONObject()
                    .put("id", s.id)
                    .put("name", s.name)
                    .put("stamp", s.stamp)
                    .put("agent", s.currentCommand)
            draft.addSession(h.profile.id, h.fingerprint, session, "")
        }
    }

    fun createSession(launch: SessionLaunch) {
        val h = current() ?: return
        if (h.creating) return
        if (store.draft(h.profile.id) != null) {
            ui.message("Review the saved draft before starting more terminals.")
            return
        }
        val navigationGeneration = terminal.generation
        scope.launch {
            h.creating = true
            registry.publish()
            val request =
                launch
                    .arguments()
                    .apply { remove("operation") }
                    .put("count", launch.count)
                    .put("workspaceId", launch.workspaceId)
                    .put("workspaceName", launch.workspaceName)
                    .toString()
            val operation = store.launchOperation(h.profile.id, request)
            val created = mutableListOf<JSONObject>()
            var draft: SharedProfile? = null
            try {
                require(launch.count in 1..8) { "Choose between 1 and 8 terminals." }
                draft = SharedProfile(requireNotNull(h.shared).document)
                var workspaceId = launch.workspaceId
                for (i in 0 until launch.count) {
                    val args = launch.arguments().put("operation", "$operation-$i").put("name", if (launch.count == 1) launch.name else "${launch.name}-${i + 1}")
                    val session = h.link.request("create", args) as JSONObject
                    created.add(session)
                    val saved =
                        session
                            .put("agent", launch.agent)
                            .put("model", launch.model)
                            .put("effort", launch.effort)
                            .put("identity", launch.identity)
                            .put("operation", "$operation-$i")
                    workspaceId = draft.addSession(h.profile.id, h.fingerprint, saved, launch.path, workspaceId, launch.workspaceName)
                }
                registry.save(h, draft)
                store.completeLaunch(h.profile.id, request)
                registry.refresh(h)
                val first = created.first()
                if (terminal.generation == navigationGeneration && current() === h && ui.value.screen != Screen.Hosts) {
                    terminal.openSession(h, first.getString("id"), first.getString("stamp"), first.getString("name"))
                } else {
                    ui.message("Created ${created.size} terminals on ${h.profile.name.ifBlank { h.profile.host }}.")
                }
            } catch (e: Exception) {
                if (created.isNotEmpty() && draft != null) store.retainDraft(h.profile.id, draft.document, h.shared?.document)
                ui.message((e.message ?: "Session creation failed.") + " Refresh before retrying; any created sessions and unsaved draft are retained.")
                runCatching { registry.refresh(h) }
            } finally {
                h.creating = false
                registry.publish()
            }
        }
    }

    fun renameSession(
        id: String,
        stamp: String,
        name: String,
    ) {
        val h = current() ?: return
        scope.launch {
            try {
                h.link.request("rename", JSONObject().put("id", id).put("stamp", stamp).put("name", name))
                registry.refresh(h)
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not rename this session.")
            }
        }
    }

    fun endSession(
        id: String,
        stamp: String,
        name: String,
    ) {
        val h = current() ?: return
        scope.launch {
            try {
                h.ending = name
                registry.publish()
                h.link.request("destroy", JSONObject().put("id", id).put("stamp", stamp))
                registry.refresh(h)
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not end this session.")
            } finally {
                h.ending = null
                registry.publish()
            }
        }
    }
}

package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.ProfileMerge
import com.adamchesters.dazedev.mobile.host.SharedProfile
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Hostless groups on this phone, terminal tab groups, and edits to host workspaces and their saved drafts. */
class WorkspaceController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val store: ProfileStore,
    private val scope: CoroutineScope,
    private val terminal: TerminalController,
    private val connect: (ConnectionProfile, String) -> Unit,
    private val showHosts: () -> Unit,
) {
    private val localGroups = store.localWorkspaces()?.let(::SharedProfile) ?: SharedProfile.empty()
    private var groupConnectQueue = listOf<String>()

    private fun current(): HostEntry? = registry.entry(ui.value.profile.id)

    fun publishLocalGroups() {
        ui.update { it.copy(localGroups = localGroups.folders()) }
    }

    private fun saveLocalGroups() {
        store.saveLocalWorkspaces(localGroups.document)
        publishLocalGroups()
    }

    fun createLocalGroup(name: String) {
        val id = localGroups.createLocalWorkspace(name.trim())
        saveLocalGroups()
        selectLocalGroup(id)
    }

    fun selectLocalGroup(id: String) {
        terminal.closeTerminal()
        groupConnectQueue = emptyList()
        ui.update {
            it.copy(
                showHosts = false,
                localGroupId = id,
                selectedSessionName = null,
                selectedSessionId = null,
                selectedPaneId = null,
                selectedFolder = null,
                groupConnectionHost = null,
                pendingTerminalConnection = null,
            )
        }
        val needs = ui.value.groupHosts()
        ui.update { it.copy(groupConnectPrompt = needs.isNotEmpty()) }
    }

    fun dismissGroupConnect() {
        ui.update { it.copy(groupConnectPrompt = false) }
    }

    fun clearGroupQueue() {
        groupConnectQueue = emptyList()
    }

    fun connectGroupHosts() {
        groupConnectQueue =
            ui.value
                .groupHosts()
                .mapNotNull { it.host?.profile?.id }
                .distinct()
        ui.update { it.copy(groupConnectPrompt = false) }
        nextGroupConnection()
    }

    fun nextGroupConnection() {
        groupConnectQueue = groupConnectQueue.filter { registry.entry(it)?.phase != ConnectionPhase.Connected }
        val id = groupConnectQueue.firstOrNull()
        if (id == null) {
            ui.update { it.copy(groupConnectionHost = null) }
            return
        }
        groupConnectQueue = groupConnectQueue.drop(1)
        val host = registry.entry(id) ?: return nextGroupConnection()
        ui.update { it.copy(groupConnectionHost = id, profile = host.profile, connectionPhase = host.phase) }
        if (host.profile.authMethod == AuthMethod.TailscaleSsh) connect(host.profile, "")
    }

    fun cloneToLocalGroup(
        sourceHost: String,
        folder: String,
        pane: String,
    ) {
        try {
            localGroups.clonePane(ui.value.localGroupId ?: return, registry.entry(sourceHost)?.shared ?: error("Connect the source host first."), folder, pane)
            saveLocalGroups()
        } catch (e: Exception) {
            ui.message(e.message ?: "Could not add this terminal view.")
        }
    }

    fun editLocalGroup(
        name: String? = null,
        remove: Boolean = false,
    ) {
        localGroups.editWorkspace(ui.value.localGroupId ?: return, name = name, remove = remove)
        saveLocalGroups()
        if (remove) showHosts()
    }

    fun groupTerminalTabs(panes: List<String>) = editTabs { profile, id -> profile.groupTabs(id, panes) }

    fun ungroupTerminalTab(pane: String) = editTabs { profile, id -> profile.ungroupTab(id, pane) }

    fun removeLocalTerminalView(pane: String) {
        try {
            localGroups.removeLocalView(ui.value.localGroupId ?: return, pane)
            saveLocalGroups()
        } catch (e: Exception) {
            ui.message(e.message ?: "Could not remove this terminal view.")
        }
    }

    private fun editTabs(edit: (SharedProfile, String) -> Unit) {
        val local = ui.value.localGroupId
        if (local != null) {
            try {
                edit(localGroups, local)
                saveLocalGroups()
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not group terminals.")
            }
            return
        }
        val folder = ui.value.selectedFolder ?: return
        editShared("Could not save terminal tabs. Draft retained.") { edit(it, folder) }
    }

    /** Saves an edit to the selected host's workspaces; a failed save keeps the draft. */
    fun editShared(
        failure: String,
        edit: (SharedProfile) -> Unit,
    ) {
        val h = current() ?: return
        scope.launch {
            try {
                val draft = SharedProfile(requireNotNull(h.shared).document)
                edit(draft)
                registry.save(h, draft)
            } catch (e: Exception) {
                ui.message(e.message ?: failure)
            }
        }
    }

    fun editWorkspace(
        id: String,
        name: String? = null,
        remove: Boolean = false,
        move: Int = 0,
        background: String? = null,
    ) = editShared("Workspace edit failed. Draft retained.") { it.editWorkspace(id, name, remove, move, background) }

    /** Points a saved terminal at a replacement session, in a hostless group or the host's workspace. */
    fun rebindPane(
        pane: String,
        session: TmuxSession,
    ) {
        if (ui.value.localGroupId != null) {
            if (localGroups.rebindPaneSession(pane, session.id, session.stamp)) saveLocalGroups()
            return
        }
        val owner = current() ?: return
        val shared = owner.shared ?: return
        scope.launch {
            try {
                val draft = SharedProfile(shared.document)
                if (draft.rebindPaneSession(pane, session.id, session.stamp)) registry.save(owner, draft)
            } catch (e: Exception) {
                ui.message("The terminal opened, but its saved workspace entry was not updated. ${e.message.orEmpty()}")
            }
        }
    }

    fun reviewDraft() {
        val h = current() ?: return
        scope.launch {
            try {
                val base = store.draftBase(h.profile.id) ?: error("This draft has no recovery base. It is kept on this phone; reload before making a fresh edit.")
                val draft = store.draft(h.profile.id) ?: error("No saved draft.")
                val fresh = registry.fresh(h)
                val merged = ProfileMerge.reapply(base, draft, fresh.document)
                h.shared = fresh
                registry.publish()
                if (current() === h) ui.update { it.copy(draftReview = merged) }
            } catch (e: Exception) {
                ui.message(e.message ?: "Draft cannot be safely reapplied. It remains saved.")
            }
        }
    }

    fun discardDraft() {
        val h = current() ?: return
        store.clearDraft(h.profile.id)
        h.conflict = false
        dismissDraftReview()
        registry.publish()
    }

    fun dismissDraftReview() {
        ui.update { it.copy(draftReview = null) }
    }

    fun applyDraftReview() {
        val h = current() ?: return
        val review = ui.value.draftReview ?: return
        scope.launch {
            try {
                registry.save(h, SharedProfile(review), recovery = true)
                dismissDraftReview()
            } catch (e: Exception) {
                ui.message(e.message ?: "Host changed again. Draft remains saved; review again.")
            }
        }
    }
}

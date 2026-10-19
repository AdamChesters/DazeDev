package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.HostSnapshot
import com.adamchesters.dazedev.mobile.host.TerminalRow

/** Which host is shown, connecting and disconnecting hosts, and opening saved terminals on other hosts. */
class ConnectionController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val terminal: TerminalController,
) {
    private var applied: HostSnapshot? = null
    lateinit var workspace: WorkspaceController

    /** Shows the host list, and the selected host's details whenever they change or [force] is set. */
    fun sync(
        hosts: List<HostSnapshot> = registry.hosts.value,
        force: Boolean = false,
    ) {
        val current = hosts.firstOrNull { it.profile.id == ui.value.profile.id }
        ui.update { it.withHosts(hosts) }
        if (current != null && (force || current != applied)) {
            applied = current
            ui.update { it.withHost(current) }
        }
    }

    fun showHosts() {
        terminal.closeTerminal()
        workspace.clearGroupQueue()
        ui.update {
            it.withoutTerminal().atHosts().copy(
                groupConnectPrompt = false,
                groupConnectionHost = null,
                selectedFolder = null,
                pendingTerminalConnection = null,
            )
        }
    }

    fun newHost() {
        terminal.closeTerminal()
        val profile = ConnectionProfile()
        ui.update {
            it.withoutTerminal().atHost(profile.id).copy(
                profile = profile,
                connectionPhase = ConnectionPhase.Disconnected,
                selectedFolder = null,
                pendingTerminalConnection = null,
            )
        }
    }

    fun selectHost(id: String) {
        val h = registry.entry(id) ?: return
        terminal.closeTerminal()
        ui.update { it.withoutTerminal().atHost(id).copy(profile = h.profile, selectedFolder = null, pendingTerminalConnection = null) }
        sync(force = true)
    }

    fun connect(
        profile: ConnectionProfile,
        secret: String,
    ) {
        val started = registry.connect(profile, secret.toCharArray(), ::verifyPending, ::connected)
        if (started) ui.update { it.copy(hostKeyChange = null) }
    }

    /** A saved terminal on another host opens only if the address reached the same host, user and socket. */
    private fun verifyPending(h: HostEntry) {
        val pending = ui.value.pendingTerminalConnection
        if (pending?.targetProfileId == h.profile.id) {
            require(pending.row.hostKey == h.fingerprint) {
                "This address reached a different host, user or tmux socket. The saved terminal was not opened. Check the connection details."
            }
        }
    }

    private fun connected(h: HostEntry) {
        if (ui.value.groupConnectionHost == h.profile.id) workspace.nextGroupConnection()
        val selected = ui.value.pendingTerminalConnection
        if (selected?.targetProfileId == h.profile.id) {
            ui.update { it.copy(pendingTerminalConnection = null) }
            registry.entry(selected.ownerId)?.let { owner ->
                ui.update { it.copy(profile = owner.profile, selectedFolder = selected.folderId) }
                sync(force = true)
            }
            terminal.openSession(h, selected.row.sessionId, selected.row.stamp, selected.row.name)
        } else {
            terminal.resume(h)
        }
    }

    fun disconnect() {
        val h = registry.entry(ui.value.profile.id) ?: return
        terminal.closeTerminal()
        registry.disconnect(h.profile.id)
        ui.update { it.withoutTerminal() }
        sync(force = true)
    }

    fun selectPane(row: TerminalRow) {
        if (row.plainShell) {
            ui.message("${row.name} is a plain shell inside the desktop app, so other devices cannot join it. On the desktop, use its tmux button to run it in a tmux session.")
            return
        }
        if (row.hostKey.isBlank()) {
            ui.message("This terminal has no saved host identity. Open its host's session list and adopt it again.")
            return
        }
        ui.update { it.copy(selectedPaneId = row.paneId) }
        val state = ui.value
        val target = state.destination(row)
        if (target.connected) {
            val h = registry.entry(target.host?.profile?.id) ?: return
            terminal.openSession(h, row.sessionId, row.stamp, row.name)
            return
        }
        val profile = target.profile()
        val pending = PendingTerminalConnection(row, state.profile.id, state.selectedFolder, profile.id)
        terminal.closeTerminal()
        ui.update {
            // The connection form shows on top of the workspace or hostless group the terminal came from.
            it.withoutTerminal().let { s -> if (s.screen == Screen.Hosts) s.atHost(profile.id) else s }.copy(
                profile = profile,
                connectionPhase = target.host?.phase ?: ConnectionPhase.Disconnected,
                selectedFolder = null,
                pendingTerminalConnection = pending,
            )
        }
        if (profile.authMethod == AuthMethod.TailscaleSsh && profile.host.isNotBlank() && profile.username.isNotBlank()) connect(profile, "")
    }

    fun cancelConnection() {
        val group = ui.value.groupConnectionHost
        if (group != null) {
            registry.cancel(group)
            workspace.clearGroupQueue()
            ui.update { it.copy(groupConnectionHost = null) }
            return
        }
        val pending = ui.value.pendingTerminalConnection
        if (pending == null) {
            disconnect()
            showHosts()
            return
        }
        registry.cancel(pending.targetProfileId)
        ui.update { it.copy(pendingTerminalConnection = null) }
        val owner = registry.entry(pending.ownerId)
        if (owner == null) {
            showHosts()
        } else {
            ui.update { (if (it.localGroupId == null) it.atHost(owner.profile.id) else it).copy(profile = owner.profile, selectedFolder = pending.folderId) }
            sync(force = true)
        }
    }
}

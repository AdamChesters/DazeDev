package com.adamchesters.dazedev.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.adamchesters.dazedev.mobile.BuildConfig
import com.adamchesters.dazedev.mobile.DazeDevApplication
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.host.BrowserAnswer
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostEvent
import com.adamchesters.dazedev.mobile.host.SessionLaunch
import com.adamchesters.dazedev.mobile.host.TerminalRow
import com.adamchesters.dazedev.mobile.releases.PublicReleaseCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Composes the app-wide host registry and the screen controllers into one UI state. */
class MainViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val registry = (application as DazeDevApplication).hosts
    private val credentials = (application as DazeDevApplication).credentials
    private val store = ProfileStore(application)
    private val ui = UiStore(DazeDevMobileUiState(profile = store.load(), secureScreen = store.secureScreen, keepConnected = store.keepConnected, keepOtherSize = store.keepOtherSize))
    val uiState: StateFlow<DazeDevMobileUiState> = ui.state.asStateFlow()
    private val terminal = TerminalController(ui, registry, viewModelScope)

    /** The open terminal's live stream, kept out of the UI state. */
    val terminalLink = terminal.link
    private val connections = ConnectionController(ui, registry, terminal)
    private val workspace = WorkspaceController(ui, registry, store, viewModelScope, terminal, connections::connect, connections::showHosts)
    private val sessions = SessionController(ui, registry, store, viewModelScope, terminal, workspace)
    private val updates = UpdateController(ui, viewModelScope, store, PublicReleaseCatalog, BuildConfig.VERSION_NAME, downloads = java.io.File(application.cacheDir, "updates"))
    private val hostKeys = HostKeyController(ui, registry, viewModelScope, (application as DazeDevApplication)::knownHosts)

    init {
        connections.workspace = workspace
        terminal.connect = { connections.connect(it, "") }
        terminal.rebindPane = workspace::rebindPane
        terminal.keepOtherSize = { ui.value.keepOtherSize }
        viewModelScope.launch { registry.hosts.collect { connections.sync(it) } }
        viewModelScope.launch { registry.events.collect(::onHostEvent) }
        viewModelScope.launch { registry.prompts.authNotice.collect { notice -> ui.update { it.copy(authNotice = notice) } } }
        viewModelScope.launch { registry.prompts.browserRequest.collect { request -> ui.update { it.copy(browserRequest = request) } } }
        hostKeys.start()
        workspace.publishLocalGroups()
        updates.start(
            application.assets
                .open("changelog.json")
                .bufferedReader()
                .use { it.readText() },
        )
    }

    private fun onHostEvent(event: HostEvent) {
        when (event) {
            is HostEvent.Message -> ui.message(event.text)
            is HostEvent.LinkLost -> terminal.linkLost(event.hostId, event.error)
            is HostEvent.Reconnected -> terminal.reattach(event.hostId)
            is HostEvent.Disconnected -> terminal.linkLost(event.hostId, "This host was disconnected. Connect again to continue.")
            is HostEvent.KeyChanged -> ui.update { it.copy(hostKeyChange = event.change) }
        }
    }

    fun checkUpdates(manual: Boolean = true) = updates.checkUpdates(manual)

    fun dismissUpdate() = updates.dismissUpdate()

    fun downloadUpdate() = updates.downloadUpdate()

    fun installStarted() = updates.installStarted()

    fun installFailed(message: String) = updates.installFailed(message)

    fun refreshReleaseHistory() = updates.refreshReleaseHistory()

    fun createLocalGroup(name: String) = workspace.createLocalGroup(name)

    fun selectLocalGroup(id: String) = workspace.selectLocalGroup(id)

    fun dismissGroupConnect() = workspace.dismissGroupConnect()

    fun connectGroupHosts() = workspace.connectGroupHosts()

    fun cloneToLocalGroup(
        sourceHost: String,
        folder: String,
        pane: String,
    ) = workspace.cloneToLocalGroup(sourceHost, folder, pane)

    fun editLocalGroup(
        name: String? = null,
        remove: Boolean = false,
    ) = workspace.editLocalGroup(name, remove)

    fun groupTerminalTabs(panes: List<String>) = workspace.groupTerminalTabs(panes)

    fun ungroupTerminalTab(pane: String) = workspace.ungroupTerminalTab(pane)

    fun removeLocalTerminalView(pane: String) = workspace.removeLocalTerminalView(pane)

    fun showHosts() = connections.showHosts()

    /** Back: leaves the terminal, cancels a connection, closes the folder, then returns to the host list. */
    fun back() {
        val s = ui.value
        when {
            s.screen is Screen.Terminal -> leaveTerminal()
            s.screen == Screen.Hosts -> Unit
            s.localGroupId == null && s.connectionPhase != ConnectionPhase.Connected -> cancelConnection()
            s.groupConnectionHost != null || s.pendingTerminalConnection != null -> cancelConnection()
            s.selectedFolder != null -> selectFolder(null)
            else -> showHosts()
        }
    }

    fun newHost() = connections.newHost()

    fun selectHost(id: String) = connections.selectHost(id)

    fun connect(
        profile: ConnectionProfile,
        secret: String,
    ) = connections.connect(profile, secret)

    fun disconnect() = connections.disconnect()

    fun selectPane(row: TerminalRow) = connections.selectPane(row)

    fun cancelConnection() = connections.cancelConnection()

    fun selectFolder(id: String?) {
        ui.update { it.copy(selectedFolder = id) }
    }

    fun setPrivateKey(
        uri: Uri,
        displayName: String,
    ) {
        val id = ui.value.profile.id
        viewModelScope.launch {
            var key: ByteArray? = null
            try {
                // The key is read once and kept encrypted on this phone; the document is not opened again.
                key = credentials.readDocument(uri.toString())
                credentials.importKey(id, key)
                ui.update { if (it.profile.id == id) it.copy(profile = it.profile.copy(keyStored = true, privateKeyUri = null, privateKeyName = displayName)) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not read this private key.")
            } finally {
                key?.fill(0)
            }
        }
    }

    /** Forgets the shown host, its stored key, passphrase and host key. */
    fun removeHost() {
        val id = ui.value.profile.id
        terminal.closeTerminal()
        viewModelScope.launch {
            try {
                registry.remove(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.message(e.message ?: "Could not remove every stored item for this host.")
            }
            connections.showHosts()
        }
    }

    fun refreshSessions() = sessions.refreshSessions()

    fun reloadProfile() = sessions.reloadProfile()

    fun selectSession(name: String) = sessions.selectSession(name)

    fun adoptStaleSession() = terminal.adoptStaleSession()

    fun dismissStaleSession() = terminal.dismissStaleSession()

    fun terminalMeasured(
        epoch: Long,
        cols: Int,
        rows: Int,
    ) = terminal.terminalMeasured(epoch, cols, rows)

    fun refreshTerminal() = terminal.refreshTerminal()

    fun reconnectTerminal() = terminal.reconnectTerminal()

    suspend fun prepareInputTarget(): String = terminal.prepareInputTarget()

    suspend fun validateInputTarget(token: String): Boolean = terminal.validateInputTarget(token)

    fun readTmuxSettings() = terminal.tmux("tmux_settings_read", "Could not read settings.") { result -> ui.update { it.copy(tmuxSettings = result as JSONObject) } }

    fun applyTmuxSetting(
        name: String,
        value: String,
    ) {
        val settings = ui.value.tmuxSettings ?: return
        terminal.tmux(
            "tmux_settings_apply",
            "Settings changed. Refresh before applying.",
            {
                it
                    .put("window", settings.optString("window"))
                    .put("revision", settings.getString("revision"))
                    .put("name", name)
                    .put("value", value)
            },
        ) { result -> ui.update { it.copy(tmuxSettings = result as JSONObject) } }
    }

    fun readWindows() =
        terminal.tmux("tmux_settings_read", "Could not read tmux windows.") { result ->
            val rows = (result as JSONObject).getJSONArray("windows")
            ui.update { it.copy(windows = windows(rows)) }
        }

    private fun windows(rows: JSONArray) =
        (0 until rows.length()).map { i ->
            val w = rows.getJSONObject(i)
            TmuxWindow(w.getString("id"), w.getString("name"), w.getBoolean("active"))
        }

    fun selectWindow(id: String) = terminal.tmux("tmux_window_select", "Window changed; refresh before choosing.", { it.put("window", id) }) { dismissWindows() }

    fun dismissWindows() {
        ui.update { it.copy(windows = emptyList()) }
    }

    fun reviewDraft() = workspace.reviewDraft()

    fun discardDraft() = workspace.discardDraft()

    fun dismissDraftReview() = workspace.dismissDraftReview()

    fun applyDraftReview() = workspace.applyDraftReview()

    fun dismissTmuxSettings() {
        ui.update { it.copy(tmuxSettings = null) }
    }

    fun leaveTerminal() = terminal.leaveTerminal()

    fun sendInput(input: String) = terminal.sendInput(input)

    fun editWorkspace(
        id: String,
        name: String? = null,
        remove: Boolean = false,
        move: Int = 0,
        background: String? = null,
    ) = workspace.editWorkspace(id, name, remove, move, background)

    fun adoptSession(name: String) = sessions.adoptSession(name)

    fun createSession(launch: SessionLaunch) = sessions.createSession(launch)

    fun renameSession(
        id: String,
        stamp: String,
        name: String,
    ) = sessions.renameSession(id, stamp, name)

    fun endSession(
        id: String,
        stamp: String,
        name: String,
    ) = sessions.endSession(id, stamp, name)

    fun answerHostKeyChallenge(
        hostId: String,
        id: Long,
        trust: Boolean,
    ) = hostKeys.answer(hostId, id, trust)

    /** Keeps the terminal through rotation and short app switches; detaches after a grace period. */
    fun setForeground(value: Boolean) {
        registry.prompts.foreground = value
        terminal.setForeground(value)
    }

    fun dismissAuthNotice() = registry.prompts.dismissAuthNotice()

    fun answerBrowserRequest(answer: BrowserAnswer) = registry.prompts.answerBrowser(answer)

    fun openHostKeys() = hostKeys.openHostKeys()

    /** Blocks screenshots and the Recents preview when on. */
    fun setSecureScreen(value: Boolean) {
        store.secureScreen = value
        ui.update { it.copy(secureScreen = value) }
    }

    /** Keeps host connections open in the background with a foreground service while hosts are connected. */
    fun setKeepConnected(value: Boolean) {
        store.keepConnected = value
        registry.keepConnected.value = value
        ui.update { it.copy(keepConnected = value) }
    }

    /** Applies to terminals opened from now on. */
    fun setKeepOtherSize(value: Boolean) {
        store.keepOtherSize = value
        ui.update { it.copy(keepOtherSize = value) }
    }

    fun notificationsDenied() {
        ui.message("Notifications are off, so the keep-connected notice is hidden. Hosts still stay connected in the background.")
    }

    fun closeHostKeys() = hostKeys.closeHostKeys()

    fun dismissHostKeyChange() = hostKeys.dismissHostKeyChange()

    fun forgetHostKey(entry: HostKeyEntry) = hostKeys.forgetHostKey(entry)

    fun consumeMessage(id: Long) {
        ui.update { if (it.message?.id == id) it.copy(message = null) else it }
    }

    /** Host connections live in the app's registry and stay open; only this screen's terminal closes. */
    override fun onCleared() {
        terminal.closeTerminal()
        registry.prompts.foreground = false
        super.onCleared()
    }
}

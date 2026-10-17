package com.adamchesters.dazedev.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.adamchesters.dazedev.mobile.BuildConfig
import com.adamchesters.dazedev.mobile.DazeDevApplication
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.ProfileStore
import com.adamchesters.dazedev.mobile.host.BackgroundWindow
import com.adamchesters.dazedev.mobile.host.BrowserAnswer
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostEvent
import com.adamchesters.dazedev.mobile.host.SessionLaunch
import com.adamchesters.dazedev.mobile.host.TerminalRow
import com.adamchesters.dazedev.mobile.releases.PublicReleaseCatalog
import com.adamchesters.dazedev.mobile.service.AlertNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Composes the app-wide host registry and the screen controllers into one UI state. */
class MainViewModel(
    application: Application,
    saved: SavedStateHandle,
) : AndroidViewModel(application) {
    private val registry = (application as DazeDevApplication).hosts
    private val credentials = (application as DazeDevApplication).credentials
    private val store = ProfileStore(application)
    private val ui = UiStore(DazeDevMobileUiState(profile = store.load(), secureScreen = store.secureScreen, backgroundWindow = registry.backgroundWindow.value, keepOtherSize = store.keepOtherSize, extraKeys = store.extraKeys, alertNotifications = store.alertNotifications))
    val uiState: StateFlow<DazeDevMobileUiState> = ui.state.asStateFlow()
    private val terminal = TerminalController(ui, registry, viewModelScope)

    /** The open terminal's live stream, kept out of the UI state. */
    val terminalLink = terminal.link
    private val connections = ConnectionController(ui, registry, terminal)
    private val workspace = WorkspaceController(ui, registry, store, viewModelScope, terminal, connections::connect, connections::showHosts)
    private val sessions = SessionController(ui, registry, store, viewModelScope, terminal, workspace)
    private val updates = UpdateController(ui, viewModelScope, store, PublicReleaseCatalog, BuildConfig.VERSION_NAME, downloads = java.io.File(application.cacheDir, "updates"))
    private val hostKeys = HostKeyController(ui, registry, viewModelScope, (application as DazeDevApplication)::knownHosts)
    private val contentFiles = ContentFiles(application)
    val uploads = UploadController(registry, terminal, viewModelScope, ui::message) { contentFiles.spool(it) }

    val previews = PreviewController(terminal, viewModelScope)
    val broadcasts = BroadcastController(ui, registry, viewModelScope)
    val repositories = RepositoryController(registry, viewModelScope)
    val dictionary = DictionaryController(java.io.File(application.filesDir, "dictionary/dictionary.json"), registry, viewModelScope)
    private val alerts = TerminalAlertCenter(ui, registry, { ui.value.alertNotifications }, { key, title, text -> AlertNotifier.notify(application, key, title, text) })

    /** The open terminal's page, registered while the terminal screen shows it. */
    val page = MutableStateFlow<TerminalPageController?>(null)

    init {
        connections.workspace = workspace
        terminal.connect = { connections.connect(it, "") }
        terminal.rebindPane = workspace::rebindPane
        terminal.keepOtherSize = { ui.value.keepOtherSize }
        terminal.wrapStream = { h, id, name, stream -> AlertingTerminal(stream, alerts.watcher(h, id, name)) }
        terminal.opened = alerts::clear
        terminal.keepAttachedInBackground = { ui.value.alertNotifications && AlertNotifier.allowed(application) }
        viewModelScope.launch { registry.hosts.collect { connections.sync(it) } }
        viewModelScope.launch { registry.events.collect(::onHostEvent) }
        viewModelScope.launch { registry.prompts.authNotice.collect { notice -> ui.update { it.copy(authNotice = notice) } } }
        viewModelScope.launch { registry.prompts.browserRequest.collect { request -> ui.update { it.copy(browserRequest = request) } } }
        hostKeys.start()
        workspace.publishLocalGroups()
        // After process death, show the same screen, host, folder and terminal again.
        saved.get<String>(NAVIGATION)?.let { json ->
            ui.update { it.restoreNavigation(json) { id -> registry.entry(id)?.profile } }
            connections.sync(force = true)
        }
        viewModelScope.launch {
            ui.state
                .map { it.navigationJson() }
                .distinctUntilChanged()
                .collect { saved[NAVIGATION] = it }
        }
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
            is HostEvent.Checked -> terminal.reattach(event.hostId)
            is HostEvent.Disconnected -> terminal.linkLost(event.hostId, "This host was disconnected. Connect again to continue.")
            is HostEvent.KeyChanged -> ui.update { it.copy(hostKeyChange = event.change) }
        }
    }

    /** Files or text shared to DazeDev, or picked with the document picker: confirm, then upload. */
    fun shared(
        uris: List<Uri>,
        text: String? = null,
    ) {
        contentFiles.clearSpool()
        uploads.offer(uris.map(contentFiles::describe), text)
    }

    /** A file on the clipboard: quick upload to the host's DazeDev uploads folder, then its path can be pasted. */
    fun clipboardFile(uri: Uri) {
        contentFiles.clearSpool()
        uploads.offer(listOf(contentFiles.describe(uri)), quick = true)
    }

    fun dismissUpload() {
        uploads.dismiss()
        if (!uploads.state.value.open) contentFiles.clearSpool()
    }

    /** Pastes uploaded paths into the terminal that was open when the upload started, without Return. */
    fun pasteUploadedPath() {
        val text = uploads.pathText()
        if (!uploads.sameTerminal()) return ui.message("The terminal changed. Copy the path instead.")
        if (page.value?.paste(text) != true) return ui.message("The path was not pasted. Wait for the terminal, then try again.")
        uploads.dismiss()
    }

    fun pasteSharedText() {
        val text = uploads.state.value.text ?: return
        if (!uploads.sameTerminal()) return ui.message("The terminal changed. Open it again to paste.")
        if (page.value?.paste(text) != true) return ui.message("Text was not pasted. Wait for the terminal, or share less than 64 KiB.")
        uploads.dismiss()
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

    /**
     * Keeps the terminal through rotation and short app switches; detaches after a grace period.
     * The registry goes first, so hosts it checks on return already show Checking to the terminal.
     */
    fun setForeground(value: Boolean) {
        registry.setForeground(value)
        registry.prompts.foreground = value
        terminal.setForeground(value)
        if (value) alerts.returned()
    }

    fun dismissAuthNotice() = registry.prompts.dismissAuthNotice()

    fun answerBrowserRequest(answer: BrowserAnswer) = registry.prompts.answerBrowser(answer)

    fun openHostKeys() = hostKeys.openHostKeys()

    /** Blocks screenshots and the Recents preview when on. */
    fun setSecureScreen(value: Boolean) {
        store.secureScreen = value
        ui.update { it.copy(secureScreen = value) }
    }

    /** How long a foreground service keeps host connections open after the app leaves the screen. */
    fun setBackgroundWindow(value: BackgroundWindow) {
        store.backgroundWindow = value
        registry.backgroundWindow.value = value
        ui.update { it.copy(backgroundWindow = value) }
    }

    /** Shows or hides the extra-keys row above the on-screen keyboard. */
    fun setExtraKeys(value: Boolean) {
        store.extraKeys = value
        ui.update { it.copy(extraKeys = value) }
    }

    /** Local notifications for completion alerts while the app is in the background. */
    fun setAlertNotifications(value: Boolean) {
        store.alertNotifications = value
        ui.update { it.copy(alertNotifications = value) }
    }

    /** Applies to terminals opened from now on. */
    fun setKeepOtherSize(value: Boolean) {
        store.keepOtherSize = value
        ui.update { it.copy(keepOtherSize = value) }
    }

    fun notificationsDenied() {
        ui.message("Notifications are off, so the background connection notice and its timer are hidden. Hosts still stay connected in the background.")
    }

    fun alertNotificationsDenied() {
        ui.message("Notifications are not allowed, so completion notifications stay off. Alerts still show as badges in the app.")
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

    private companion object {
        const val NAVIGATION = "navigation"
    }
}

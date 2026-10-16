package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.HostStore
import com.adamchesters.dazedev.mobile.ssh.HostKeyChangedException
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns every host connection for the whole app process, so connections outlive screens and view models.
 * All state changes run on [scope], which is the main thread in the app.
 */
class HostRegistry(
    val store: HostStore,
    private val links: HostLinkFactory,
    private val helpers: suspend () -> HelperSources,
    private val readKey: suspend (ConnectionProfile) -> ByteArray,
    private val openBrowser: (String) -> String?,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) {
    private val entries = linkedMapOf<String, HostEntry>()
    private val hostList = MutableStateFlow<List<HostSnapshot>>(emptyList())
    private val hostEvents = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)

    val hosts: StateFlow<List<HostSnapshot>> = hostList.asStateFlow()
    val events: SharedFlow<HostEvent> = hostEvents.asSharedFlow()

    /** Host key checks, sign-in messages and browser links waiting for the phone user. */
    val prompts = HostPrompts(openBrowser, ::message)

    val all: Collection<HostEntry> get() = entries.values

    /** The "Keep hosts connected in the background" setting. */
    val keepConnected = MutableStateFlow(false)

    init {
        store.loadAll().forEach(::add)
        publish()
    }

    fun entry(id: String?): HostEntry? = id?.let(entries::get)

    fun snapshot(id: String?): HostSnapshot? = hostList.value.firstOrNull { it.profile.id == id }

    fun publish() {
        hostList.value = entries.values.map { it.snapshot(store.hasDraft(it.profile.id)) }
    }

    private fun add(profile: ConnectionProfile): HostEntry {
        val keyPrompts = HostKeyPromptCoordinator()
        lateinit var entry: HostEntry
        val ssh = links.ssh(profile.id, keyPrompts) { text -> scope.launch { prompts.showAuthNotice(entry, text) } }
        entry = HostEntry(profile, keyPrompts, ssh)
        entry.link = links.host(ssh, scope) { url -> prompts.openHostUrl(entry, url) }
        entry.watchers =
            scope.launch {
                launch { keyPrompts.challenge.collect { prompts.challengeChanged(entry, it) } }
                entry.link.events.collect { event -> if (event is HostLinkEvent.Lost) linkLost(entry, event.reason) }
            }
        entries[profile.id] = entry
        return entry
    }

    private fun message(text: String) {
        hostEvents.tryEmit(HostEvent.Message(text))
    }

    /**
     * Connects a host. [verify] may refuse the host after its identity is known; [onConnected] runs once
     * its sessions are listed. Returns false when the connection did not start.
     */
    fun connect(
        profile: ConnectionProfile,
        secret: String,
        verify: (HostEntry) -> Unit = {},
        onConnected: suspend (HostEntry) -> Unit = {},
    ): Boolean {
        val normalized =
            profile.copy(
                host = profile.host.trim(),
                username = profile.username.trim(),
                workingDirectory = profile.workingDirectory.trim(),
                socket = profile.socket.trim(),
            )
        normalized.validationError(secret)?.let {
            message(it)
            return false
        }
        val h = entries[normalized.id] ?: add(normalized)
        if (h.connectJob?.isCompleted == false) {
            message("Wait for this host connection to finish or cancel.")
            return false
        }
        h.profile = normalized
        h.phase = ConnectionPhase.Connecting
        h.linkError = null
        publish()
        h.connectJob = scope.launch { runConnect(h, normalized, secret, verify, onConnected) }
        return true
    }

    private suspend fun runConnect(
        h: HostEntry,
        normalized: ConnectionProfile,
        secret: String,
        verify: (HostEntry) -> Unit,
        onConnected: suspend (HostEntry) -> Unit,
    ) {
        var key: ByteArray? = null
        try {
            if (normalized.authMethod == AuthMethod.PrivateKey) key = readKey(normalized)
            h.ssh.connect(normalized, secret, key)
            val sources = helpers()
            val ready = h.link.start(sources.host, normalized.socket, sources.browser, h.label)
            h.fingerprint = "${ready.getString("machine")}:${ready.getString("user")}:${normalized.socket}"
            verify(h)
            h.profile = normalized.copy(fingerprint = h.fingerprint)
            h.shared = loadShared(h)
            h.sessionsLoaded = false
            h.allowBrowser = false
            h.sessions = emptyList()
            h.phase = ConnectionPhase.Connected
            store.save(h.profile)
            publish()
            prompts.clearAuthNotice(h)
            // Sessions first: slow agent and repository scans load afterwards.
            loadSessions(h)
            onConnected(h)
            h.background?.cancel()
            h.background =
                scope.launch {
                    loadCapabilities(h)
                    loadRepositories(h)
                }
        } catch (e: HostKeyChangedException) {
            drop(h)
            hostEvents.tryEmit(HostEvent.KeyChanged(HostKeyChange(h.profile.id, h.label, e.notice)))
        } catch (e: TimeoutCancellationException) {
            drop(h)
            message("${h.label} did not answer in time. Check the host and network, then connect again.")
        } catch (e: CancellationException) {
            h.link.close()
            h.ssh.disconnectNow()
            prompts.clearAuthNotice(h)
            throw e
        } catch (e: Exception) {
            drop(h)
            message(e.message ?: "Could not connect this host.")
        } finally {
            key?.fill(0)
        }
    }

    private suspend fun drop(h: HostEntry) {
        h.link.close()
        h.ssh.disconnect()
        h.phase = ConnectionPhase.Disconnected
        publish()
        prompts.clearAuthNotice(h)
    }

    /** Stops a connection attempt or an open connection without a link error. */
    fun cancel(id: String?) {
        val h = entry(id) ?: return
        h.connectJob?.cancel()
        h.link.close()
        h.ssh.disconnectNow()
        h.phase = ConnectionPhase.Disconnected
        publish()
    }

    fun disconnect(id: String?) {
        val h = entry(id) ?: return
        h.connectJob?.cancel()
        h.background?.cancel()
        h.link.close()
        h.ssh.disconnectNow()
        h.phase = ConnectionPhase.Disconnected
        h.linkError = null
        publish()
    }

    /** Disconnects every host, for example from the keep-connected notification. */
    fun disconnectAll() {
        entries.values.filter { it.phase != ConnectionPhase.Disconnected }.forEach { h ->
            disconnect(h.profile.id)
            hostEvents.tryEmit(HostEvent.Disconnected(h.profile.id))
        }
    }

    private fun linkLost(
        h: HostEntry,
        reason: String,
    ) {
        if (h.phase != ConnectionPhase.Connected) return
        h.background?.cancel()
        h.link.close()
        h.ssh.disconnectNow()
        h.phase = ConnectionPhase.Disconnected
        val error = "Lost connection to ${h.label}. $reason"
        h.linkError = error
        hostEvents.tryEmit(HostEvent.LinkLost(h.profile.id, error))
        publish()
    }

    private suspend fun loadShared(h: HostEntry): SharedProfile {
        val shared = SharedProfile(h.link.request("profile_load") as JSONObject)
        shared.rebindOwner(h.profile.id, h.fingerprint, HostReference.fromProfile(h.profile))
        return shared
    }

    suspend fun loadSessions(h: HostEntry) {
        val sessions = h.link.request("sessions") as JSONArray
        h.sessions = (0 until sessions.length()).map { i -> HostReplies.session(sessions.getJSONObject(i)) }
        h.sessionsLoaded = true
        publish()
    }

    private suspend fun loadCapabilities(h: HostEntry) {
        try {
            h.agents = HostReplies.agents(h.link.request("capabilities") as JSONObject)
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (h.phase == ConnectionPhase.Connected) message("Could not list agents on ${h.label}: ${e.message}")
        }
    }

    private suspend fun loadRepositories(h: HostEntry) {
        try {
            val list = (h.link.request("repositories") as JSONObject).getJSONArray("repos")
            h.repositories = (0 until list.length()).map { list.getJSONObject(it).getString("path") }
            publish()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (h.phase == ConnectionPhase.Connected) message("Could not list repositories on ${h.label}: ${e.message}")
        }
    }

    /** Profile and sessions; repositories refresh in the background. */
    suspend fun refresh(h: HostEntry) {
        h.shared = loadShared(h)
        loadSessions(h)
        scope.launch { loadRepositories(h) }
    }

    /** Loads the host profile again and clears a conflict, keeping any saved draft. */
    suspend fun reload(h: HostEntry) {
        h.shared = loadShared(h)
        h.conflict = false
        publish()
        refresh(h)
    }

    /** Loads the host's current profile without changing the shown one. */
    suspend fun fresh(h: HostEntry): SharedProfile = loadShared(h)

    suspend fun save(
        h: HostEntry,
        draft: SharedProfile,
        recovery: Boolean = false,
    ) = h.edits.withLock {
        draft.rebindOwner(h.profile.id, h.fingerprint, HostReference.fromProfile(h.profile))
        require(recovery || store.draft(h.profile.id) == null) { "Review the saved draft before making another edit. Your draft is kept." }
        store.retainDraft(h.profile.id, draft.document, h.shared?.document)
        try {
            val args = JSONObject().put("profile", draft.document).put("revision", draft.revision)
            h.shared = SharedProfile(h.link.request("profile_save", args) as JSONObject)
            h.conflict = false
            store.clearDraft(h.profile.id)
            publish()
        } catch (e: Exception) {
            if (e.message?.contains("CONFLICT") == true) {
                h.conflict = true
                publish()
            }
            throw e
        }
    }

    fun answerChallenge(
        hostId: String,
        id: Long,
        trust: Boolean,
    ) {
        prompts.answerChallenge(entry(hostId) ?: return, id, trust)
    }
}

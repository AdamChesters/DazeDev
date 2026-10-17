package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.Credentials
import com.adamchesters.dazedev.mobile.data.HostStore
import com.adamchesters.dazedev.mobile.ssh.HostKeyChangedException
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
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
    private val credentials: Credentials,
    private val openBrowser: (String) -> String?,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    /** Milliseconds that keep counting while the phone sleeps (elapsedRealtime in the app). */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val entries = linkedMapOf<String, HostEntry>()
    private val hostList = MutableStateFlow<List<HostSnapshot>>(emptyList())
    private val hostEvents = MutableSharedFlow<HostEvent>(extraBufferCapacity = 64)

    val hosts: StateFlow<List<HostSnapshot>> = hostList.asStateFlow()
    val events: SharedFlow<HostEvent> = hostEvents.asSharedFlow()

    /** Host key checks, sign-in messages and browser links waiting for the phone user. */
    val prompts = HostPrompts(openBrowser, ::message)

    val all: Collection<HostEntry> get() = entries.values

    /** The "Stay connected in the background" setting. */
    val backgroundWindow = MutableStateFlow(BackgroundWindow.Default)
    private val reconnects = AutoReconnect(this)

    private val visible = MutableStateFlow(true)

    /** Whether the app is on screen (between ON_START and ON_STOP). */
    val foreground: StateFlow<Boolean> = visible.asStateFlow()
    private val windowEnd = MutableStateFlow<Long?>(null)

    /** When the running background window closes, in [clock] time; null in the foreground or without a limit. */
    val backgroundEndsAt: StateFlow<Long?> = windowEnd.asStateFlow()
    private var backgroundSince = 0L
    private var windowTimer: Job? = null

    /** Why connections closed in the background, shown once on return. */
    private var closedInBackground: String? = null

    /** Hosts to reconnect when the phone's network returns. */
    private val waitingForNetwork = linkedSetOf<String>()

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
        secret: CharArray,
        verify: (HostEntry) -> Unit = {},
        onConnected: suspend (HostEntry) -> Unit = {},
        quiet: Boolean = false,
    ): Boolean {
        val normalized =
            profile.copy(
                host = profile.host.trim(),
                username = profile.username.trim(),
                workingDirectory = profile.workingDirectory.trim(),
                socket = profile.socket.trim(),
            )
        normalized.validationError(secret)?.let {
            secret.fill('\u0000')
            message(it)
            return false
        }
        val h = entries[normalized.id] ?: add(normalized)
        if (h.connectJob?.isCompleted == false) {
            secret.fill('\u0000')
            if (!quiet) message("Wait for this host connection to finish or cancel.")
            return false
        }
        h.profile = normalized
        h.phase = ConnectionPhase.Connecting
        h.linkError = null
        h.resumeOnReturn = false
        publish()
        h.connectJob = scope.launch { runConnect(h, normalized, secret, verify, onConnected) { if (!quiet) message(it) } }
        return true
    }

    private suspend fun runConnect(
        h: HostEntry,
        normalized: ConnectionProfile,
        secret: CharArray,
        verify: (HostEntry) -> Unit,
        onConnected: suspend (HostEntry) -> Unit,
        say: (String) -> Unit,
    ) {
        var key: ByteArray? = null
        var remembered: CharArray? = null
        try {
            if (normalized.authMethod == AuthMethod.PrivateKey) {
                key = credentials.key(normalized)
                if (secret.isEmpty() && normalized.rememberPassphrase) remembered = credentials.passphrase(normalized.id)
            }
            val used = remembered ?: secret
            h.ssh.connect(normalized, used, key)
            val sources = helpers()
            val ready = h.link.start(sources.host, normalized.socket, sources.browser, h.label)
            h.fingerprint = "${ready.getString("machine")}:${ready.getString("user")}:${normalized.socket}"
            verify(h)
            h.profile = credentials.connected(normalized, key, used).copy(fingerprint = h.fingerprint)
            h.unattended = h.profile.authMethod == AuthMethod.TailscaleSsh ||
                (h.profile.authMethod == AuthMethod.PrivateKey && h.profile.keyStored && (used.isEmpty() || h.profile.rememberPassphrase))
            h.shared = loadShared(h)
            h.sessionsLoaded = false
            h.tmuxIgnoreSize = null
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
            say("${h.label} did not answer in time. Check the host and network, then connect again.")
        } catch (e: CancellationException) {
            h.link.close()
            h.ssh.disconnectNow()
            prompts.clearAuthNotice(h)
            throw e
        } catch (e: Exception) {
            drop(h)
            say(e.message ?: "Could not connect this host.")
        } finally {
            key?.fill(0)
            remembered?.fill('\u0000')
            secret.fill('\u0000')
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
        stopReconnect(h)
        h.connectJob?.cancel()
        stopCheck(h)
        h.resumeOnReturn = false
        h.link.close()
        h.ssh.disconnectNow()
        h.phase = ConnectionPhase.Disconnected
        publish()
    }

    fun disconnect(id: String?) {
        val h = entry(id) ?: return
        waitingForNetwork.remove(h.profile.id)
        stopReconnect(h)
        h.connectJob?.cancel()
        h.background?.cancel()
        stopCheck(h)
        h.resumeOnReturn = false
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

    /**
     * The phone has no network, so every open connection is gone. Hosts that can reconnect without
     * a secret wait for the network (on screen or inside the background window); the others show
     * Disconnected.
     */
    fun networkLost() {
        entries.values.forEach { h ->
            val reconnecting = h.reconnect?.isActive == true
            // Attempts without a network only fail; they start again when it returns.
            stopReconnect(h)
            if (h.phase == ConnectionPhase.Connected) {
                linkLost(h, "The phone lost its network connection.", retry = false)
            } else if (!reconnecting) {
                return@forEach
            }
            if (h.unattended && mayReconnect()) {
                waitingForNetwork.add(h.profile.id)
                h.linkError = "Waiting for the network to reconnect ${h.label}."
            } else if (h.unattended) {
                h.resumeOnReturn = true
            }
        }
        publish()
    }

    /** The network is back: reconnect waiting hosts with the usual backoff. */
    fun networkAvailable() {
        val waiting = waitingForNetwork.mapNotNull(::entry)
        waitingForNetwork.clear()
        waiting
            .filter { it.unattended && it.phase == ConnectionPhase.Disconnected }
            .forEach {
                if (mayReconnect()) {
                    reconnects.start(it, "Lost connection to ${it.label}. The network changed.")
                } else {
                    it.resumeOnReturn = true
                }
            }
    }

    private fun linkLost(
        h: HostEntry,
        reason: String,
        retry: Boolean = true,
        waits: List<Long>? = null,
    ) {
        if (h.phase != ConnectionPhase.Connected) return
        h.background?.cancel()
        stopCheck(h)
        h.link.close()
        h.ssh.disconnectNow()
        h.phase = ConnectionPhase.Disconnected
        val error = "Lost connection to ${h.label}. $reason"
        h.linkError = error
        hostEvents.tryEmit(HostEvent.LinkLost(h.profile.id, error))
        publish()
        if (!retry || !h.unattended) return
        if (mayReconnect()) reconnects.start(h, error, waits) else h.resumeOnReturn = true
    }

    internal fun reconnected(h: HostEntry) {
        hostEvents.tryEmit(HostEvent.Reconnected(h.profile.id))
    }

    /** Automatic reconnects run while the app is on screen, or in the background inside the window. */
    internal fun mayReconnect(): Boolean = visible.value || (backgroundWindow.value.keepsConnections && windowEnd.value.let { it == null || clock() < it })

    /**
     * Called on ON_START and ON_STOP. Leaving starts the background window; returning closes it,
     * checks every connected host and reconnects unattended hosts that closed meanwhile.
     */
    fun setForeground(value: Boolean) {
        if (visible.value == value) return
        visible.value = value
        windowTimer?.cancel()
        windowTimer = null
        if (!value) {
            backgroundSince = clock()
            val limit = backgroundWindow.value.millis?.takeIf { it > 0 }
            windowEnd.value = limit?.let { backgroundSince + it }
            if (limit != null) {
                windowTimer =
                    scope.launch {
                        delay(limit)
                        closeBackgroundConnections()
                    }
            }
            return
        }
        // The timer may not have run if the phone slept or Android paused the app.
        val end = windowEnd.value
        windowEnd.value = null
        if (end != null && clock() >= end) closeBackgroundConnections()
        closedInBackground?.let { reason ->
            closedInBackground = null
            message(reason)
        }
        entries.values.forEach { h ->
            when {
                h.phase == ConnectionPhase.Connected -> {
                    check(h)
                }

                h.resumeOnReturn && h.unattended && h.phase == ConnectionPhase.Disconnected && !h.reconnecting -> {
                    reconnects.start(h, h.linkError ?: "Lost connection to ${h.label}.", reconnects.onReturn)
                }
            }
        }
        publish()
    }

    /** The background window ended: disconnect cleanly and record why. */
    private fun closeBackgroundConnections() {
        windowTimer = null
        val reason = "Disconnected after ${backgroundWindow.value.label} in the background."
        var closed = false
        entries.values
            .filter { it.phase != ConnectionPhase.Disconnected || it.reconnecting || it.profile.id in waitingForNetwork || it.resumeOnReturn }
            .forEach { h ->
                val resume = h.unattended
                disconnect(h.profile.id)
                h.linkError = reason
                h.resumeOnReturn = resume
                hostEvents.tryEmit(HostEvent.LinkLost(h.profile.id, reason))
                closed = true
            }
        if (closed) closedInBackground = reason
        publish()
    }

    /** Asks a connected host to answer within a few seconds; a dead link goes through the lost-link path. */
    private fun check(h: HostEntry) {
        stopCheck(h)
        h.checking = true
        h.check =
            scope.launch {
                val alive = answers(h)
                h.checking = false
                h.check = null
                when {
                    h.phase != ConnectionPhase.Connected -> {
                        publish()
                    }

                    alive -> {
                        publish()
                        hostEvents.tryEmit(HostEvent.Checked(h.profile.id))
                    }

                    else -> {
                        linkLost(h, "It did not answer after the app returned to the screen.", waits = reconnects.onReturn)
                    }
                }
            }
    }

    private suspend fun answers(h: HostEntry): Boolean {
        if (!h.ssh.isConnected || !h.link.isOpen) return false
        // A write to a dead connection can block, so the wait does not depend on the probe returning.
        val ping = scope.async { runCatching { h.link.probe(PROBE_TIMEOUT_MS) }.isSuccess }
        return withTimeoutOrNull(PROBE_TIMEOUT_MS + 500) { ping.await() } ?: false.also { ping.cancel() }
    }

    private fun stopReconnect(h: HostEntry) {
        h.reconnect?.cancel()
        h.reconnect = null
        h.reconnecting = false
    }

    private fun stopCheck(h: HostEntry) {
        h.check?.cancel()
        h.check = null
        h.checking = false
    }

    /** Forgets a saved host: its connection, profile, stored key, passphrase and host key. */
    suspend fun remove(id: String) {
        val h = entries.remove(id) ?: return
        stopReconnect(h)
        h.connectJob?.cancel()
        h.background?.cancel()
        h.watchers?.cancel()
        stopCheck(h)
        h.link.close()
        h.ssh.disconnectNow()
        prompts.challengeChanged(h, null)
        prompts.clearAuthNotice(h)
        store.remove(id)
        credentials.forget(id)
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

    private companion object {
        /** Long enough for a phone radio and a Tailscale path to wake, short enough to feel immediate. */
        const val PROBE_TIMEOUT_MS = 6_000L
    }
}

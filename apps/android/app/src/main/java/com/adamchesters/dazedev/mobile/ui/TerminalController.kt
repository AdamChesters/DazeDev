package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.ssh.AttachRequest
import com.adamchesters.dazedev.mobile.ssh.SshTerminalOpener
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import com.adamchesters.dazedev.mobile.ssh.TerminalOpener
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Attaches, measures, feeds and detaches the open terminal. Each attach has a generation; older ones are dropped. */
class TerminalController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val scope: CoroutineScope,
    private val opener: TerminalOpener = SshTerminalOpener,
) {
    private var foreground = true
    private var backgroundDetach: Job? = null
    private var attachment: Job? = null
    private val terminalSize = MutableStateFlow<Pair<Int, Int>?>(null)
    private var attachmentGeneration = 0L

    /** The session to reopen once its host reconnects: host id to (session id, stamp, name). */
    var resumeAfterConnect: Pair<String, Triple<String, String, String>>? = null

    /** Connects a host without a secret (Tailscale SSH). */
    var connect: (ConnectionProfile) -> Unit = {}

    /** Points the selected saved terminal at a replacement session. */
    var rebindPane: (String, TmuxSession) -> Unit = { _, _ -> }

    val generation get() = attachmentGeneration

    private fun target(): HostEntry? = registry.entry(ui.value.selectedTargetHost)

    fun openSession(
        h: HostEntry,
        id: String,
        stamp: String,
        name: String,
    ) {
        closeTerminal()
        ui.update {
            it.copy(
                selectedSessionName = name,
                selectedSessionId = id,
                selectedStamp = stamp,
                selectedTargetHost = h.profile.id,
                selectedTargetLabel = h.label,
                tmuxSettings = null,
                windows = emptyList(),
                terminalEpoch = attachmentGeneration,
                terminalError = null,
                staleSession = null,
            )
        }
        if (!foreground) return
        val generation = attachmentGeneration
        attachment = scope.launch { attach(h, id, stamp, name, generation) }
    }

    private suspend fun attach(
        h: HostEntry,
        id: String,
        stamp: String,
        name: String,
        generation: Long,
    ) {
        var stream: TerminalLink? = null
        try {
            h.link.request("session_context", JSONObject().put("id", id).put("stamp", stamp), 15_000)
            if (generation != attachmentGeneration) return
            val browser =
                try {
                    h.link.request("browser_prepare") as JSONObject
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ui.message("Browser forwarding is unavailable. You can still use this terminal and tap its web links.")
                    null
                }
            // Attach at the measured size so other clients do not reflow several times.
            val size =
                withTimeoutOrNull(10_000) {
                    terminalSize.filterNotNull().first()
                    delay(200)
                    terminalSize.value
                } ?: terminalSize.value ?: (80 to 24)
            if (generation != attachmentGeneration) return
            stream = opener.open(h.ssh, scope, AttachRequest(h.profile.socket, id, stamp, size.first, size.second, browser))
            if (generation != attachmentGeneration) {
                stream.close()
                return
            }
            terminalSize.value?.let { stream.resize(it.first, it.second) }
            ui.update { it.copy(terminal = stream) }
        } catch (e: TimeoutCancellationException) {
            stream?.close()
            if (generation == attachmentGeneration) ui.update { it.copy(terminalError = "The terminal did not connect in time. Try again.") }
        } catch (e: CancellationException) {
            stream?.close()
            throw e
        } catch (e: Exception) {
            stream?.close()
            if (generation == attachmentGeneration) openFailed(h, id, stamp, name, e)
        }
    }

    private suspend fun openFailed(
        h: HostEntry,
        id: String,
        stamp: String,
        name: String,
        e: Exception,
    ) {
        val message = e.message.orEmpty()
        if (!(message.contains("Session changed") || message.contains("no longer exists") || message.contains("ended or restarted"))) {
            ui.update { it.copy(terminalError = message.ifBlank { "Terminal connection failed." }) }
            return
        }
        runCatching { registry.loadSessions(h) }
        val replacement = h.sessions.firstOrNull { it.name == name && (it.id != id || it.stamp != stamp) }
        ui.update { it.copy(terminalError = "Session ended or restarted.", staleSession = StaleSession(h.profile.id, name, replacement)) }
    }

    /** Opens the running session with the saved name and points the saved terminal at it. */
    fun adoptStaleSession() {
        val stale = ui.value.staleSession ?: return
        val h = registry.entry(stale.hostId) ?: return
        val session = stale.replacement ?: return
        ui.update { it.copy(staleSession = null) }
        ui.value.selectedPaneId?.let { pane -> rebindPane(pane, session) }
        openSession(h, session.id, session.stamp, session.name)
    }

    fun dismissStaleSession() {
        ui.update { it.copy(staleSession = null) }
    }

    /** Called by the terminal view with its fitted size for the current attachment. */
    fun terminalMeasured(
        epoch: Long,
        cols: Int,
        rows: Int,
    ) {
        if (epoch == ui.value.terminalEpoch) terminalSize.value = cols.coerceIn(2, 500) to rows.coerceIn(2, 300)
    }

    fun closeTerminal() {
        attachmentGeneration++
        attachment?.cancel()
        attachment = null
        terminalSize.value = null
        ui.value.terminal?.close()
        ui.update { it.copy(terminal = null, terminalEpoch = attachmentGeneration, tmuxSettings = null, windows = emptyList(), draftReview = null) }
    }

    fun refreshTerminal() {
        val s = ui.value
        val h = target() ?: return
        openSession(h, s.selectedSessionId ?: return, s.selectedStamp ?: return, s.selectedSessionName.orEmpty())
    }

    /** Reattaches the terminal, reconnecting its host first when the link is gone. */
    fun reconnectTerminal() {
        val s = ui.value
        val h = target() ?: return
        if (h.phase == ConnectionPhase.Connected && h.link.isOpen) {
            refreshTerminal()
            return
        }
        if (h.phase == ConnectionPhase.Connecting) return
        val session = Triple(s.selectedSessionId ?: return, s.selectedStamp ?: return, s.selectedSessionName.orEmpty())
        resumeAfterConnect = h.profile.id to session
        if (h.profile.authMethod == AuthMethod.TailscaleSsh) {
            ui.update { it.copy(terminalError = null) }
            connect(h.profile)
            return
        }
        // Password and key hosts need the secret again; the session reopens after connecting.
        closeTerminal()
        ui.update {
            it
                .copy(
                    profile = h.profile,
                    showHosts = false,
                    localGroupId = null,
                    selectedFolder = null,
                    selectedSessionName = null,
                    selectedSessionId = null,
                    selectedTargetHost = null,
                    terminalError = null,
                ).withHost(registry.snapshot(h.profile.id))
        }
    }

    /** Opens the session a reconnect was waiting for. */
    fun resume(h: HostEntry): Boolean {
        val resume = resumeAfterConnect?.takeIf { it.first == h.profile.id }?.second ?: return false
        resumeAfterConnect = null
        openSession(h, resume.first, resume.second, resume.third)
        return true
    }

    /** Opens the shown terminal again after its host reconnected by itself. */
    fun reattach(hostId: String) {
        val s = ui.value
        if (foreground && s.selectedTargetHost == hostId && s.selectedSessionId != null && s.terminal == null && s.staleSession == null) refreshTerminal()
    }

    /** Closes the terminal when its host link is lost. */
    fun linkLost(
        hostId: String,
        error: String,
    ) {
        if (ui.value.selectedTargetHost != hostId) return
        closeTerminal()
        ui.update { it.copy(terminalError = error) }
    }

    suspend fun prepareInputTarget(): String {
        val state = ui.value
        val h = target() ?: error("Terminal host is disconnected.")
        require(state.terminal?.connected == true) { "Reconnect this terminal before preparing input." }
        val args = JSONObject().put("id", state.selectedSessionId).put("stamp", state.selectedStamp)
        val context = h.link.request("session_context", args, 15_000) as String
        val pid = context.split(':').getOrNull(3)?.toIntOrNull() ?: error("Terminal process is unavailable.")
        val process = h.link.request("process_context", JSONObject().put("pid", pid), 15_000).toString()
        return JSONObject()
            .put("host", h.profile.id)
            .put("fingerprint", h.fingerprint)
            .put("session", state.selectedSessionId)
            .put("stamp", state.selectedStamp)
            .put("context", context)
            .put("process", process)
            .toString()
    }

    suspend fun validateInputTarget(token: String): Boolean =
        try {
            val old = JSONObject(token)
            val state = ui.value
            if (old.getString("host") != state.selectedTargetHost || old.getString("session") != state.selectedSessionId || old.getString("stamp") != state.selectedStamp) {
                false
            } else {
                val fresh = JSONObject(prepareInputTarget())
                listOf("fingerprint", "context", "process").all { fresh.getString(it) == old.getString(it) }
            }
        } catch (e: Exception) {
            false
        }

    /** Runs a tmux request for the open session; the result is dropped if another terminal opened meanwhile. */
    fun tmux(
        method: String,
        failure: String,
        args: (JSONObject) -> JSONObject = { it },
        onResult: (Any) -> Unit,
    ) {
        val state = ui.value
        val h = target() ?: return
        scope.launch {
            try {
                val result = h.link.request(method, args(JSONObject().put("session", state.selectedSessionId).put("stamp", state.selectedStamp)))
                if (ui.value.terminalEpoch == state.terminalEpoch) onResult(result)
            } catch (e: Exception) {
                ui.message(e.message ?: failure)
            }
        }
    }

    fun leaveTerminal() {
        closeTerminal()
        ui.update { it.copy(selectedSessionName = null, selectedSessionId = null, selectedTargetHost = null) }
    }

    fun sendInput(input: String) {
        if (ui.value.terminal?.send(input.toByteArray()) != true) ui.message("Input was not sent. Reconnect the terminal; input is never replayed.")
    }

    /** Keeps the terminal through rotation and short app switches; detaches after a grace period. */
    fun setForeground(value: Boolean) {
        foreground = value
        backgroundDetach?.cancel()
        backgroundDetach = null
        if (!value) {
            backgroundDetach =
                scope.launch {
                    delay(BACKGROUND_GRACE_MS)
                    closeTerminal()
                }
            return
        }
        val s = ui.value
        if (s.selectedSessionId != null && s.staleSession == null && attachment?.isActive != true && s.terminal?.connected != true && target()?.phase == ConnectionPhase.Connected) {
            refreshTerminal()
        }
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 120_000L
    }
}

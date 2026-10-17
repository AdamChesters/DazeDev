package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.Broadcast
import com.adamchesters.dazedev.mobile.host.BroadcastCandidate
import com.adamchesters.dazedev.mobile.host.BroadcastScope
import com.adamchesters.dazedev.mobile.host.BroadcastSnapshot
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.TerminalRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BroadcastUiState(
    val open: Boolean = false,
    val text: String = "",
    val scope: BroadcastScope = BroadcastScope.Workspace,
    /** The frozen recipient list the user reviews; cleared when the text or scope changes. */
    val snapshot: BroadcastSnapshot? = null,
    val busy: Boolean = false,
    val result: String = "",
    val notice: String = "",
)

/** One message typed or dictated into several terminals, after the user reviews the recipients. */
class BroadcastController(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val scope: CoroutineScope,
) {
    private val broadcast = MutableStateFlow(BroadcastUiState())
    val state: StateFlow<BroadcastUiState> = broadcast.asStateFlow()

    fun open(text: String = "") {
        val s = ui.value
        broadcast.value = BroadcastUiState(open = true, text = text, scope = if (s.currentFolder() == null) BroadcastScope.Terminal else BroadcastScope.Workspace)
    }

    fun close() {
        if (!broadcast.value.busy) broadcast.value = BroadcastUiState()
    }

    fun setText(text: String) {
        if (broadcast.value.busy || broadcast.value.result.isNotEmpty()) return
        broadcast.update { it.copy(text = text, snapshot = null, notice = "") }
    }

    fun setScope(value: BroadcastScope) {
        if (broadcast.value.busy || broadcast.value.result.isNotEmpty()) return
        broadcast.update { it.copy(scope = value, snapshot = null, notice = "") }
    }

    /** Saved terminals in [scope], starting with the open terminal. */
    fun candidates(scope: BroadcastScope): List<BroadcastCandidate> {
        val s = ui.value
        val open =
            listOfNotNull(
                s.selectedSessionId?.let { id ->
                    val h = registry.entry(s.selectedTargetHost)
                    BroadcastCandidate("${s.selectedTargetLabel} / ${s.selectedSessionName.orEmpty()}", h?.profile?.id, h?.fingerprint.orEmpty(), id, s.selectedStamp.orEmpty())
                },
            )

        fun rows(
            rows: List<TerminalRow>,
            workspace: String,
        ) = rows.map { row ->
            val target = s.destination(row)
            BroadcastCandidate("${target.label} / $workspace / ${row.name}", target.host?.profile?.id, row.hostKey, row.sessionId, row.stamp)
        }
        val openHost = s.hosts.firstOrNull { it.profile.id == s.selectedTargetHost }
        return open +
            when (scope) {
                BroadcastScope.Terminal -> emptyList()
                BroadcastScope.Workspace -> s.currentFolder()?.let { rows(it.rows, it.name) }.orEmpty()
                BroadcastScope.Host -> openHost?.folders.orEmpty().flatMap { rows(it.rows, it.name) }
                BroadcastScope.All -> s.hosts.flatMap { h -> h.folders.flatMap { rows(it.rows, it.name) } } + s.localGroups.flatMap { rows(it.rows, it.name) }
            }
    }

    /** Lists running sessions on every connected host involved, then freezes the recipients. */
    private suspend fun collect(scope: BroadcastScope): BroadcastSnapshot {
        val candidates = candidates(scope)
        val hosts =
            candidates
                .mapNotNull { it.hostId }
                .distinct()
                .mapNotNull(registry::entry)
                .filter { it.phase == ConnectionPhase.Connected }
        for (h in hosts) runCatching { registry.loadSessions(h) }
        return Broadcast.snapshot(candidates) { id ->
            registry
                .entry(id)
                ?.takeIf { it.phase == ConnectionPhase.Connected && it.sessionsLoaded }
                ?.sessions
                ?.map { it.id to it.stamp }
        }
    }

    fun review() {
        val s = broadcast.value
        if (s.busy || s.result.isNotEmpty()) return
        Broadcast.problem(s.text)?.let { problem ->
            broadcast.update { it.copy(notice = problem) }
            return
        }
        broadcast.update { it.copy(busy = true, notice = "") }
        scope.launch {
            try {
                val snapshot = collect(s.scope)
                broadcast.update { if (it.text == s.text && it.scope == s.scope) it.copy(snapshot = snapshot, busy = false) else it.copy(busy = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                broadcast.update { it.copy(busy = false, notice = e.message ?: "Could not list recipients.") }
            }
        }
    }

    /** Sends once to the reviewed recipients; if they changed meanwhile, shows the new list to confirm again. */
    fun send() {
        val s = broadcast.value
        val reviewed = s.snapshot ?: return
        if (s.busy || s.result.isNotEmpty() || reviewed.recipients.isEmpty()) return
        broadcast.update { it.copy(busy = true, notice = "") }
        scope.launch {
            try {
                val fresh = collect(s.scope)
                if (fresh.revision != reviewed.revision) {
                    broadcast.update { it.copy(snapshot = fresh, busy = false, notice = "Broadcast recipients changed. Review the updated list and confirm again.") }
                    return@launch
                }
                val result =
                    Broadcast.Delivery(fresh, s.text).send { recipient, text ->
                        val h = registry.entry(recipient.hostId)?.takeIf { it.phase == ConnectionPhase.Connected } ?: error("Host disconnected.")
                        Broadcast.deliver(h.ssh, h.profile.socket, recipient, text)
                    }
                var text = "Sent to ${result.delivered.size} terminal sessions. ${fresh.skipped.size} skipped; ${fresh.mirrors} mirrors deduplicated. No delivery will be retried."
                if (result.failed.isNotEmpty()) text += "\nDelivery failed or is uncertain for ${result.failed.size} sessions. Some text may already have arrived:\n" + result.failed.joinToString("\n")
                broadcast.update { it.copy(busy = false, result = text) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                broadcast.update { it.copy(busy = false, notice = e.message ?: "Broadcast failed.") }
            }
        }
    }
}

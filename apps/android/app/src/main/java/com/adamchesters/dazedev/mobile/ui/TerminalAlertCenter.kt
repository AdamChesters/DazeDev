package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.AlertThrottle
import com.adamchesters.dazedev.mobile.host.HostEntry
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.TerminalAlertWatcher
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach

/** The open terminal's stream, with its output also read for completion alerts. */
class AlertingTerminal(
    private val inner: TerminalLink,
    private val watcher: TerminalAlertWatcher,
) : TerminalLink by inner {
    override val output: Flow<ByteArray> = inner.output.onEach { bytes -> runCatching { watcher.feed(bytes) } }
}

/**
 * Completion alerts from the open terminal: a message while the app is on screen; otherwise a badge on the
 * terminal's row and, when turned on, a local notification (only while the host stays connected).
 */
class TerminalAlertCenter(
    private val ui: UiStore,
    private val registry: HostRegistry,
    private val notificationsOn: () -> Boolean,
    private val notify: (key: String, title: String, text: String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val throttle = AlertThrottle()

    fun watcher(
        h: HostEntry,
        sessionId: String,
        name: String,
    ) = TerminalAlertWatcher(clock, { registry.foreground.value }) { text -> alert(h.profile.id, sessionId, name.ifBlank { h.label }, text) }

    fun alert(
        hostId: String,
        sessionId: String,
        name: String,
        text: String,
    ) {
        val key = key(hostId, sessionId)
        if (!throttle.allow(key, clock())) return
        if (registry.foreground.value) {
            ui.message("$name: $text")
            return
        }
        ui.update { it.copy(alerts = it.alerts + (key to text)) }
        if (notificationsOn()) notify(key, name, text)
    }

    /** The user opened this terminal; its badge is seen. */
    fun clear(
        hostId: String,
        sessionId: String,
    ) {
        val key = key(hostId, sessionId)
        if (key in ui.value.alerts) ui.update { it.copy(alerts = it.alerts - key) }
    }

    /** Back on screen with the alerting terminal open: say what happened, then clear its badge. */
    fun returned() {
        val s = ui.value
        val key = key(s.selectedTargetHost ?: return, s.selectedSessionId ?: return)
        val text = s.alerts[key] ?: return
        ui.message("While you were away, ${s.selectedSessionName.orEmpty().ifBlank { "this terminal" }}: $text")
        ui.update { it.copy(alerts = it.alerts - key) }
    }

    companion object {
        fun key(
            hostId: String,
            sessionId: String,
        ) = "$hostId|$sessionId"
    }
}

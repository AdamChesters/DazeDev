package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.ssh.HostKeyChallenge
import com.adamchesters.dazedev.mobile.ssh.HostKeyNotice
import com.adamchesters.dazedev.mobile.tmux.TmuxSession

enum class ConnectionPhase { Disconnected, Connecting, Connected }

enum class BrowserAnswer { Once, Connection, Deny }

data class HostKeyChange(
    val hostId: String,
    val label: String,
    val notice: HostKeyNotice,
)

data class BrowserRequest(
    val id: Long,
    val host: String,
    val url: String,
)

data class AuthNotice(
    val hostId: String,
    val host: String,
    val text: String,
    val url: String?,
)

data class HostChallenge(
    val hostId: String,
    val host: String,
    val challenge: HostKeyChallenge,
)

/** What the screens show for one saved host. */
data class HostSnapshot(
    val profile: ConnectionProfile,
    val phase: ConnectionPhase,
    val fingerprint: String,
    val folders: List<WorkspaceFolder>,
    val sessions: List<TmuxSession>,
    val sessionsLoaded: Boolean,
    val repositories: List<String>,
    val agents: List<AgentCapability>,
    val conflict: Boolean,
    val refreshing: Boolean,
    val creating: Boolean,
    val ending: String?,
    val linkError: String?,
    val recoveryDraft: Boolean,
)

sealed interface HostEvent {
    data class Message(
        val text: String,
    ) : HostEvent

    data class LinkLost(
        val hostId: String,
        val error: String,
    ) : HostEvent

    data class Reconnected(
        val hostId: String,
    ) : HostEvent

    data class Disconnected(
        val hostId: String,
    ) : HostEvent

    data class KeyChanged(
        val change: HostKeyChange,
    ) : HostEvent
}

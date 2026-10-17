package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshLink
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex

/** Live state for one saved host. Only the registry's main-thread scope changes it. */
class HostEntry(
    var profile: ConnectionProfile,
    val prompts: HostKeyPromptCoordinator,
    val ssh: SshLink,
) {
    lateinit var link: HostLink
    val label get() = profile.name.ifBlank { profile.host }
    var phase = ConnectionPhase.Disconnected
    var sessionsLoaded = false
    var linkError: String? = null
    var allowBrowser = false
    var background: Job? = null
    var shared: SharedProfile? = null
    var fingerprint = profile.fingerprint
    var sessions = listOf<TmuxSession>()
    var repositories = listOf<String>()
    var agents = listOf<AgentCapability>()
    var conflict = false
    var refreshing = false
    var creating = false
    var ending: String? = null
    var connectJob: Job? = null
    var watchers: Job? = null
    var reconnect: Job? = null

    /** An automatic reconnect is waiting or running. */
    var reconnecting = false

    /** A liveness check after the app returned to the screen. The phase stays Connected meanwhile. */
    var check: Job? = null
    var checking = false

    /** The link ended while the app was in the background; reconnect when it returns (unattended hosts only). */
    var resumeOnReturn = false

    /** The last connection needed no typed secret, so it can reconnect unattended. */
    var unattended = false

    /** Whether this host's tmux can attach without resizing others (3.2 or newer); null until checked. */
    var tmuxIgnoreSize: Boolean? = null
    val edits = Mutex()

    fun snapshot(recoveryDraft: Boolean) =
        HostSnapshot(
            profile = profile,
            phase = phase,
            fingerprint = fingerprint,
            folders = shared?.folders().orEmpty(),
            sessions = sessions,
            sessionsLoaded = sessionsLoaded,
            repositories = repositories,
            agents = agents,
            conflict = conflict,
            refreshing = refreshing,
            creating = creating,
            ending = ending,
            linkError = linkError,
            checking = checking,
            reconnecting = reconnecting,
            recoveryDraft = recoveryDraft,
        )
}

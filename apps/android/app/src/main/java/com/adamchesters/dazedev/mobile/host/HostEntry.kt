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
            recoveryDraft = recoveryDraft,
        )
}

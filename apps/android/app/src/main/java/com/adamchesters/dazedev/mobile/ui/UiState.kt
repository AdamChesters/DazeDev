package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.AgentCapability
import com.adamchesters.dazedev.mobile.host.AuthNotice
import com.adamchesters.dazedev.mobile.host.BrowserRequest
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostKeyChange
import com.adamchesters.dazedev.mobile.host.HostSnapshot
import com.adamchesters.dazedev.mobile.host.TerminalRow
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
import com.adamchesters.dazedev.mobile.releases.MobileUpdate
import com.adamchesters.dazedev.mobile.releases.ReleaseEntry
import com.adamchesters.dazedev.mobile.ssh.HostKeyChallenge
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

data class TmuxWindow(
    val id: String,
    val name: String,
    val active: Boolean,
)

data class UserMessage(
    val id: Long,
    val text: String,
)

data class PendingTerminalConnection(
    val row: TerminalRow,
    val ownerId: String,
    val folderId: String?,
    val targetProfileId: String,
)

data class StaleSession(
    val hostId: String,
    val name: String,
    val replacement: TmuxSession?,
)

data class HostKeyEntry(
    val hostId: String,
    val label: String,
    val type: String,
    val fingerprint: String,
)

data class HostSummary(
    val profile: ConnectionProfile,
    val phase: ConnectionPhase,
    val workspaceCount: Int,
    val fingerprint: String = "",
    val folders: List<WorkspaceFolder> = emptyList(),
)

data class DazeDevMobileUiState(
    val localGroups: List<WorkspaceFolder> = emptyList(),
    val localGroupId: String? = null,
    val groupConnectPrompt: Boolean = false,
    val groupConnectionHost: String? = null,
    val selectedPaneId: String? = null,
    val releaseHistory: List<ReleaseEntry> = emptyList(),
    val releaseHistoryStatus: String = "",
    val mobileUpdate: MobileUpdate? = null,
    val updateProgress: String? = null,
    val updateError: String? = null,
    /** Path of a downloaded APK whose SHA-256 matched the release's SHA256SUMS. */
    val verifiedUpdate: String? = null,
    val checkingUpdates: Boolean = false,
    val profile: ConnectionProfile = ConnectionProfile(),
    val hosts: List<HostSummary> = emptyList(),
    val showHosts: Boolean = true,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Disconnected,
    val folders: List<WorkspaceFolder> = emptyList(),
    val selectedFolder: String? = null,
    val sessions: List<TmuxSession> = emptyList(),
    val repositories: List<String> = emptyList(),
    val agents: List<AgentCapability> = emptyList(),
    val selectedSessionName: String? = null,
    val selectedSessionId: String? = null,
    val selectedStamp: String? = null,
    val selectedTargetHost: String? = null,
    val selectedTargetLabel: String = "",
    val terminal: TerminalLink? = null,
    val terminalEpoch: Long = 0,
    val tmuxSettings: JSONObject? = null,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isCreating: Boolean = false,
    val endingSessionName: String? = null,
    val hostKeyChallenge: HostKeyChallenge? = null,
    val challengeHost: String = "",
    val challengeHostId: String = "",
    val conflict: Boolean = false,
    val recoveryDraft: Boolean = false,
    val draftReview: JSONObject? = null,
    val windows: List<TmuxWindow> = emptyList(),
    val message: UserMessage? = null,
    val pendingTerminalConnection: PendingTerminalConnection? = null,
    val sessionsLoading: Boolean = false,
    val terminalError: String? = null,
    val staleSession: StaleSession? = null,
    val linkError: String? = null,
    val hostKeyChange: HostKeyChange? = null,
    val hostKeys: List<HostKeyEntry>? = null,
    val browserRequest: BrowserRequest? = null,
    val authNotice: AuthNotice? = null,
    val secureScreen: Boolean = false,
    val keepConnected: Boolean = false,
    val keepOtherSize: Boolean = false,
)

fun DazeDevMobileUiState.withHosts(hosts: List<HostSnapshot>) = copy(hosts = hosts.map { HostSummary(it.profile, it.phase, it.folders.size, it.fingerprint, it.folders) })

/** Shows [host]'s details when it is the selected host. */
fun DazeDevMobileUiState.withHost(host: HostSnapshot?): DazeDevMobileUiState {
    if (host == null || host.profile.id != profile.id) return this
    return copy(
        profile = host.profile,
        connectionPhase = host.phase,
        folders = host.folders,
        sessions = host.sessions,
        repositories = host.repositories,
        agents = host.agents,
        conflict = host.conflict,
        recoveryDraft = host.recoveryDraft,
        isRefreshing = host.refreshing,
        isCreating = host.creating,
        endingSessionName = host.ending,
        sessionsLoading = host.phase == ConnectionPhase.Connected && !host.sessionsLoaded,
        linkError = if (host.phase == ConnectionPhase.Connected) null else host.linkError,
    )
}

/** The screen state shared by the view model and its controllers. */
class UiStore(
    initial: DazeDevMobileUiState,
) {
    private val ids = AtomicLong(1)
    val state = MutableStateFlow(initial)
    val value get() = state.value

    fun update(change: (DazeDevMobileUiState) -> DazeDevMobileUiState) = state.update(change)

    fun message(text: String) = update { it.copy(message = UserMessage(ids.getAndIncrement(), text)) }
}

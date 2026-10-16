package com.adamchesters.dazedev.mobile.ui

/** Only what the terminal screen shows, so unrelated app state (such as a snackbar) does not recompose it. */
@androidx.compose.runtime.Immutable
data class TerminalScreenState(
    val selectedTargetHost: String?,
    val selectedSessionId: String?,
    val selectedStamp: String?,
    val selectedSessionName: String?,
    val selectedTargetLabel: String,
    val selectedPaneId: String?,
    val terminal: com.adamchesters.dazedev.mobile.ssh.TerminalLink?,
    val terminalEpoch: Long,
    val terminalError: String?,
    val staleSession: Boolean,
    val isSending: Boolean,
    val tabs: List<com.adamchesters.dazedev.mobile.host.TerminalRow>,
)

fun DazeDevMobileUiState.terminalScreenState(terminal: com.adamchesters.dazedev.mobile.ssh.TerminalLink?) =
    TerminalScreenState(
        selectedTargetHost,
        selectedSessionId,
        selectedStamp,
        selectedSessionName,
        selectedTargetLabel,
        selectedPaneId,
        terminal,
        terminalEpoch,
        terminalError,
        staleSession != null,
        isSending,
        terminalTabs(),
    )

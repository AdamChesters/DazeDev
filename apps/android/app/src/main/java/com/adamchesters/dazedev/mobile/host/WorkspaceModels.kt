package com.adamchesters.dazedev.mobile.host

data class TerminalRow(
    val paneId: String,
    val name: String,
    val sessionId: String,
    val stamp: String,
    val hostId: String,
    val hostKey: String,
    val directory: String,
    val hostDetails: HostReference? = null,
    /** A desktop plain shell: it runs inside the desktop app, with no tmux session to join. */
    val plainShell: Boolean = false,
)

data class TerminalTabGroup(
    val id: String,
    val panes: List<String>,
)

data class WorkspaceFolder(
    val id: String,
    val name: String,
    val rows: List<TerminalRow>,
    val background: String = "",
    val tabGroups: List<TerminalTabGroup> = emptyList(),
)

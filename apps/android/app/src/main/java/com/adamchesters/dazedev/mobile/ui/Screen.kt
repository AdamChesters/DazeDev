package com.adamchesters.dazedev.mobile.ui

/** Where the user is. The last entry of the back stack is shown; Back removes it. */
sealed interface Screen {
    data object Hosts : Screen

    /** One host: its connection form, workspaces or session list. */
    data class Host(
        val hostId: String,
    ) : Screen

    data class LocalGroup(
        val groupId: String,
    ) : Screen

    data class Terminal(
        val hostId: String,
        val sessionId: String,
    ) : Screen
}

val DazeDevMobileUiState.screen: Screen get() = backStack.lastOrNull() ?: Screen.Hosts

/** The hostless workspace being shown, also while one of its terminals is open. */
val DazeDevMobileUiState.localGroupId: String? get() = backStack.firstNotNullOfOrNull { (it as? Screen.LocalGroup)?.groupId }

fun DazeDevMobileUiState.atHosts() = copy(backStack = listOf(Screen.Hosts))

fun DazeDevMobileUiState.atHost(id: String) = copy(backStack = listOf(Screen.Hosts, Screen.Host(id)))

fun DazeDevMobileUiState.atLocalGroup(id: String) = copy(backStack = listOf(Screen.Hosts, Screen.LocalGroup(id)))

/** Shows a terminal on top of the current screen, replacing any open one. */
fun DazeDevMobileUiState.withTerminal(
    hostId: String,
    sessionId: String,
) = copy(backStack = backStack.filter { it !is Screen.Terminal } + Screen.Terminal(hostId, sessionId))

/** Leaves the terminal screen and forgets its session. */
fun DazeDevMobileUiState.withoutTerminal() = copy(backStack = backStack.filter { it !is Screen.Terminal }.ifEmpty { listOf(Screen.Hosts) }, selectedSessionName = null, selectedSessionId = null)

package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamchesters.dazedev.mobile.host.AgentCapability
import com.adamchesters.dazedev.mobile.host.SessionLaunch
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import java.time.Duration
import java.time.Instant
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    uiState: DazeDevMobileUiState,
    contentPadding: PaddingValues,
    onRefresh: () -> Unit,
    onDisconnect: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCreateSession: (SessionLaunch) -> Unit,
    onEndSession: (String, String, String) -> Unit,
    onAdoptSession: (String) -> Unit,
    onBack: () -> Unit,
    onRenameSession: (String, String, String) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<TmuxSession?>(null) }
    var renameText by rememberSaveable { mutableStateOf("") }
    var showNewSession by rememberSaveable { mutableStateOf(false) }
    var pendingId by rememberSaveable { mutableStateOf("") }
    var pendingStamp by rememberSaveable { mutableStateOf("") }
    var sessionPendingEnd by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Column {
                        Text(uiState.profile.host, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "${uiState.profile.username}@${uiState.profile.host}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (uiState.isRefreshing) {
                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh sessions")
                        }
                    }
                    IconButton(onClick = onDisconnect) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Disconnect SSH")
                    }
                },
            )

            Row(
                modifier =
                    Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .semantics { contentDescription = "SSH connected" },
                ) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.primary,
                    ) {}
                }
                Text(
                    text = if (uiState.sessionsLoading) "SSH connected. Loading tmux sessions…" else "SSH connected. ${uiState.sessions.size} tmux ${if (uiState.sessions.size == 1) "session" else "sessions"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.sessions.isEmpty() && uiState.sessionsLoading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (uiState.sessions.isEmpty() && !uiState.isRefreshing) {
                EmptySessions(
                    modifier =
                        Modifier
                            .weight(1f)
                            .widthIn(max = 900.dp)
                            .fillMaxWidth()
                            .align(Alignment.CenterHorizontally)
                            .padding(32.dp),
                    onCreate = { showNewSession = true },
                )
            } else {
                LazyColumn(
                    modifier =
                        Modifier
                            .widthIn(max = 900.dp)
                            .fillMaxSize()
                            .align(Alignment.CenterHorizontally),
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            top = 4.dp,
                            end = 16.dp,
                            bottom = 100.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(uiState.sessions, key = TmuxSession::name) { session ->
                        Column {
                            SessionCard(
                                session = session,
                                onClick = { onSelectSession(session.name) },
                                onEnd = {
                                    sessionPendingEnd = session.name
                                    pendingId = session.id
                                    pendingStamp = session.stamp
                                },
                                isEnding = uiState.endingSessionName == session.name,
                                endEnabled = uiState.endingSessionName == null,
                            )
                            Row {
                                TextButton(onClick = { onAdoptSession(session.name) }) { Text("Add to workspaces") }
                                TextButton(onClick = {
                                    renameTarget = session
                                    renameText = session.name
                                }) { Text("Rename") }
                            }
                        }
                    }
                }
            }
        }

        if (uiState.sessions.isNotEmpty()) {
            ExtendedFloatingActionButton(
                onClick = { showNewSession = true },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New session") },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp),
            )
        }
    }

    renameTarget?.let { target ->
        AlertDialog(onDismissRequest = { renameTarget = null }, title = { Text("Rename session") }, text = { OutlinedTextField(renameText, { renameText = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = {
            TextButton(onClick = {
                onRenameSession(target.id, target.stamp, renameText)
                renameTarget = null
            }, enabled = renameText.matches(Regex("[\\w-]+"))) { Text("Rename") }
        }, dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } })
    }
    if (showNewSession) {
        NewSessionDialog(
            defaultDirectory = uiState.profile.workingDirectory,
            isCreating = uiState.isCreating,
            repositories = uiState.repositories,
            agents = uiState.agents,
            folders = uiState.folders,
            onDismiss = { showNewSession = false },
            onCreate = { launch ->
                showNewSession = false
                onCreateSession(launch)
            },
        )
    }

    sessionPendingEnd?.let { sessionName ->
        EndSessionDialog(
            sessionName = sessionName,
            attachedClients =
                uiState.sessions
                    .firstOrNull { it.name == sessionName }
                    ?.attachedClients
                    ?: 0,
            onDismiss = { sessionPendingEnd = null },
            onConfirm = {
                sessionPendingEnd = null
                onEndSession(pendingId, pendingStamp, sessionName)
            },
        )
    }
}

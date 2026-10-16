package com.adamchesters.dazedev.mobile.ui

import android.content.Intent
import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.adamchesters.dazedev.mobile.host.BrowserAnswer
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import kotlinx.coroutines.launch

@Composable
fun DazeDevMobileApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSwitcher by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    BackHandler(
        enabled = uiState.selectedSessionName != null,
        onBack = viewModel::leaveTerminal,
    )

    val privateKeyPicker =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                viewModel.setPrivateKey(
                    uri = uri,
                    displayName =
                        queryDisplayName(
                            context.contentResolver.query(
                                uri,
                                arrayOf(OpenableColumns.DISPLAY_NAME),
                                null,
                                null,
                                null,
                            ),
                        ) ?: uri.lastPathSegment.orEmpty().ifBlank { "Private key" },
                )
            }
        }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                    Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.secureScreen) {
        val window = (context as? android.app.Activity)?.window ?: return@LaunchedEffect
        if (uiState.secureScreen) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    LaunchedEffect(uiState.message?.id) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.consumeMessage(message.id)
    }

    BackHandler(enabled = !uiState.showHosts && uiState.selectedSessionName == null) {
        if (uiState.groupConnectionHost != null || uiState.pendingTerminalConnection != null) {
            viewModel.cancelConnection()
        } else if (uiState.selectedFolder != null) {
            viewModel.selectFolder(null)
        } else {
            viewModel.showHosts()
        }
    }

    BackHandler(enabled = !uiState.showHosts && uiState.localGroupId == null && uiState.selectedSessionName == null && uiState.connectionPhase != ConnectionPhase.Connected) { viewModel.cancelConnection() }

    val terminalState =
        remember(
            uiState.selectedTargetHost,
            uiState.selectedSessionId,
            uiState.selectedStamp,
            uiState.selectedSessionName,
            uiState.selectedTargetLabel,
            uiState.selectedPaneId,
            uiState.terminal,
            uiState.terminalEpoch,
            uiState.terminalError,
            uiState.staleSession,
            uiState.isSending,
            uiState.localGroups,
            uiState.folders,
            uiState.selectedFolder,
            uiState.localGroupId,
        ) { uiState.terminalScreenState() }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        when {
            uiState.showHosts -> {
                HostWorkspaceScreen(uiState, contentPadding, viewModel)
            }

            uiState.localGroupId != null && uiState.selectedSessionName == null && uiState.groupConnectionHost == null && uiState.pendingTerminalConnection == null -> {
                LocalWorkspaceScreen(uiState, contentPadding, viewModel)
            }

            uiState.groupConnectionHost != null || uiState.pendingTerminalConnection != null || (uiState.localGroupId == null && uiState.connectionPhase != ConnectionPhase.Connected && uiState.selectedSessionName == null) -> {
                ConnectionScreen(
                    uiState = uiState,
                    contentPadding = contentPadding,
                    onPickPrivateKey = { privateKeyPicker.launch(arrayOf("*/*")) },
                    onConnect = viewModel::connect,
                    onCancel = viewModel::cancelConnection,
                )
            }

            uiState.selectedSessionName == null && uiState.selectedFolder != "__sessions" -> {
                HostWorkspaceScreen(uiState, contentPadding, viewModel)
            }

            uiState.selectedSessionName == null -> {
                SessionListScreen(
                    uiState = uiState,
                    contentPadding = contentPadding,
                    onRefresh = viewModel::refreshSessions,
                    onDisconnect = viewModel::disconnect,
                    onSelectSession = viewModel::selectSession,
                    onCreateSession = viewModel::createSession,
                    onEndSession = viewModel::endSession,
                    onAdoptSession = viewModel::adoptSession,
                    onBack = { viewModel.selectFolder(null) },
                    onRenameSession = viewModel::renameSession,
                )
            }

            else -> {
                TerminalScreen(
                    uiState = terminalState,
                    contentPadding = contentPadding,
                    onBack = viewModel::leaveTerminal,
                    onRefresh = viewModel::refreshTerminal,
                    onSend = viewModel::sendInput,
                    onSettings = viewModel::readTmuxSettings,
                    onSwitchTerminal = { showSwitcher = true },
                    onSelectTab = viewModel::selectPane,
                    onWindows = viewModel::readWindows,
                    onPrepareTarget = viewModel::prepareInputTarget,
                    onValidateTarget = viewModel::validateInputTarget,
                    onReconnect = viewModel::reconnectTerminal,
                    onMeasured = viewModel::terminalMeasured,
                    onMessage = { text ->
                        coroutineScope.launch {
                            snackbarHostState.currentSnackbarData?.dismiss()
                            snackbarHostState.showSnackbar(text)
                        }
                    },
                )
            }
        }
    }

    if (showSwitcher) {
        TerminalSwitcher(uiState, { showSwitcher = false }) { hostId, row ->
            val folder =
                uiState.hosts
                    .firstOrNull { it.profile.id == hostId }
                    ?.folders
                    ?.firstOrNull { it.rows.any { pane -> pane.paneId == row.paneId } }
            showSwitcher = false
            viewModel.selectHost(hostId)
            viewModel.selectFolder(folder?.id)
            viewModel.selectPane(row)
        }
    }
    if (uiState.groupConnectPrompt) {
        val needs = uiState.groupHosts()
        AlertDialog(
            onDismissRequest = viewModel::dismissGroupConnect,
            title = { Text("Connect to ${needs.size} hosts?") },
            text = { Text(needs.joinToString("\n") { it.label + if (it.host == null) " · add this host first" else "" } + "\n\nEach host keeps its own authentication. Already connected hosts stay connected.") },
            confirmButton = { TextButton(onClick = viewModel::connectGroupHosts, enabled = needs.any { it.host != null }) { Text("Connect hosts") } },
            dismissButton = { TextButton(onClick = viewModel::dismissGroupConnect) { Text("Keep disconnected") } },
        )
    }
    uiState.mobileUpdate?.let { update ->
        AlertDialog(
            onDismissRequest = viewModel::dismissUpdate,
            title = { Text("Android update available") },
            text = { Text("DazeDev Mobile ${update.version} is available from the public GitHub repository. Download the APK and confirm installation in Android.\n\n${update.notes.take(1500)}") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(update.url))) }
                        .onFailure { coroutineScope.launch { snackbarHostState.showSnackbar("Could not open the download. Try again from Changelog.") } }
                    viewModel.dismissUpdate()
                }) { Text("Download APK") }
            },
            dismissButton = { TextButton(onClick = viewModel::dismissUpdate) { Text("Later") } },
        )
    }
    if (uiState.windows.isNotEmpty()) TmuxWindowPicker(uiState.windows, viewModel::selectWindow, viewModel::dismissWindows)
    uiState.draftReview?.let { review -> DraftReview(review, viewModel::applyDraftReview, viewModel::dismissDraftReview) }
    uiState.tmuxSettings?.let { settings -> TmuxSettingsSheet(settings, viewModel::applyTmuxSetting, viewModel::dismissTmuxSettings) }
    uiState.staleSession?.let { stale ->
        val replacement = stale.replacement
        AlertDialog(
            onDismissRequest = viewModel::dismissStaleSession,
            title = { Text("Session ended or restarted") },
            text = {
                Text(
                    if (replacement != null) {
                        "“${stale.name}” was closed or its tmux server restarted. A session with the same name is running now. Open it and update this saved terminal?"
                    } else {
                        "No session named “${stale.name}” is running now. Go back to start a new one or pick another session."
                    },
                )
            },
            confirmButton = {
                if (replacement != null) {
                    Button(onClick = viewModel::adoptStaleSession) { Text("Open current session") }
                } else {
                    TextButton(onClick = {
                        viewModel.dismissStaleSession()
                        viewModel.leaveTerminal()
                    }) { Text("Back") }
                }
            },
            dismissButton = { if (replacement != null) TextButton(onClick = viewModel::dismissStaleSession) { Text("Not now") } },
        )
    }
    HostDialogs(uiState, viewModel) { error -> coroutineScope.launch { snackbarHostState.showSnackbar(error) } }
}

private fun queryDisplayName(cursor: Cursor?): String? =
    cursor?.use {
        if (!it.moveToFirst()) return@use null
        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index < 0) null else it.getString(index)
    }

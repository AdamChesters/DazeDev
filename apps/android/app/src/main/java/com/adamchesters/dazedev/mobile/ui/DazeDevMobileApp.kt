package com.adamchesters.dazedev.mobile.ui

import android.content.Intent
import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun DazeDevMobileApp(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var showSwitcher by remember {mutableStateOf(false)}
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    BackHandler(
        enabled = uiState.connectionPhase == ConnectionPhase.Connected &&
            uiState.selectedSessionName != null,
        onBack = viewModel::leaveTerminal,
    )

    val privateKeyPicker = rememberLauncherForActivityResult(
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
                displayName = queryDisplayName(context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null,
                )) ?: uri.lastPathSegment.orEmpty().ifBlank { "Private key" },
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.setForeground(true)
                Lifecycle.Event.ON_STOP -> viewModel.setForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(uiState.message?.id) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.text)
        viewModel.consumeMessage(message.id)
    }

    BackHandler(enabled = !uiState.showHosts && uiState.selectedSessionName == null) {
        if (uiState.selectedFolder != null) viewModel.selectFolder(null) else viewModel.showHosts()
    }

    BackHandler(enabled = !uiState.showHosts && uiState.connectionPhase != ConnectionPhase.Connected) {viewModel.cancelConnection()}

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { contentPadding ->
        when {
            uiState.showHosts -> HostWorkspaceScreen(uiState,contentPadding,viewModel)
            uiState.connectionPhase != ConnectionPhase.Connected -> ConnectionScreen(
                uiState = uiState,
                contentPadding = contentPadding,
                onPickPrivateKey = { privateKeyPicker.launch(arrayOf("*/*")) },
                onConnect = viewModel::connect,
                onCancel = viewModel::cancelConnection,
            )
            uiState.selectedSessionName == null && uiState.selectedFolder != "__sessions" -> HostWorkspaceScreen(uiState,contentPadding,viewModel)
            uiState.selectedSessionName == null -> SessionListScreen(
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
            else -> TerminalScreen(
                uiState = uiState,
                contentPadding = contentPadding,
                onBack = viewModel::leaveTerminal,
                onRefresh = viewModel::refreshTerminal,
                onSend = viewModel::sendInput,
                onSettings = viewModel::readTmuxSettings,
                onSwitchTerminal = {showSwitcher=true},
                onWindows = viewModel::readWindows,
                onPrepareTarget = viewModel::prepareInputTarget,
                onValidateTarget = viewModel::validateInputTarget,
                onMessage = { text ->
                    coroutineScope.launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        snackbarHostState.showSnackbar(text)
                    }
                },
            )
        }
    }

    if(showSwitcher) TerminalSwitcher(uiState,{showSwitcher=false}) {hostId,row->showSwitcher=false;viewModel.selectHost(hostId);viewModel.selectPane(row)}
    if(uiState.windows.isNotEmpty()) TmuxWindowPicker(uiState.windows,viewModel::selectWindow,viewModel::dismissWindows)
    uiState.draftReview?.let {review->DraftReview(review,viewModel::applyDraftReview,viewModel::dismissDraftReview)}
    uiState.tmuxSettings?.let {settings->TmuxSettingsSheet(settings,viewModel::applyTmuxSetting,viewModel::dismissTmuxSettings)}
    uiState.hostKeyChallenge?.let { challenge ->
        val promptHostId=uiState.challengeHostId
        AlertDialog(
            onDismissRequest = {
                viewModel.answerHostKeyChallenge(promptHostId,challenge.id, false)
            },
            title = { Text("Verify SSH server") },
            text = {
                Text(
                    "${uiState.challengeHost}: Check this SSH fingerprint with the server administrator before you trust it.\n\n${challenge.message}",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.answerHostKeyChallenge(promptHostId,challenge.id, true)
                }) {
                    Text("Trust key")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.answerHostKeyChallenge(promptHostId,challenge.id, false)
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private fun queryDisplayName(cursor: Cursor?): String? = cursor?.use {
    if (!it.moveToFirst()) return@use null
    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    if (index < 0) null else it.getString(index)
}

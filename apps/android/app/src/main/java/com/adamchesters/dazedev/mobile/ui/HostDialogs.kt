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

/** Questions and warnings from hosts: key checks, sign-in messages and browser links. */
@Composable
fun HostDialogs(
    uiState: DazeDevMobileUiState,
    viewModel: MainViewModel,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    uiState.hostKeyChange?.let { change ->
        AlertDialog(
            onDismissRequest = viewModel::dismissHostKeyChange,
            title = { Text("Warning: SSH host key changed") },
            text = { Text("The key that ${change.label} sent does not match the saved key. Someone may be intercepting the connection. DazeDev did not connect.\n\nNew ${change.notice.type} key:\n${change.notice.fingerprint}\n\nOnly if you know the server key was replaced (for example after a reinstall), forget the old key in Saved host keys, then connect again and check the new fingerprint.") },
            confirmButton = { TextButton(onClick = viewModel::dismissHostKeyChange) { Text("Close") } },
            dismissButton = { TextButton(onClick = viewModel::openHostKeys) { Text("Saved host keys") } },
        )
    }
    uiState.hostKeys?.let { keys -> HostKeyManager(keys, viewModel::forgetHostKey, viewModel::closeHostKeys) }
    uiState.browserRequest?.let { request ->
        AlertDialog(
            onDismissRequest = { viewModel.answerBrowserRequest(BrowserAnswer.Deny) },
            title = { Text("Open link from ${request.host}?") },
            text = { Text("${request.host} wants to open:\n\n${request.url.take(2000)}") },
            confirmButton = {
                Column {
                    TextButton(onClick = { viewModel.answerBrowserRequest(BrowserAnswer.Once) }) { Text("Open") }
                    TextButton(onClick = { viewModel.answerBrowserRequest(BrowserAnswer.Connection) }) { Text("Allow for this connection") }
                }
            },
            dismissButton = { TextButton(onClick = { viewModel.answerBrowserRequest(BrowserAnswer.Deny) }) { Text("Don't open") } },
        )
    }
    uiState.authNotice?.let { notice ->
        AlertDialog(
            onDismissRequest = viewModel::dismissAuthNotice,
            title = { Text("Message from ${notice.host}") },
            text = { Text(notice.text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { notice.url?.let { url -> TextButton(onClick = { BrowserLauncher.open(context, url)?.let(onError) }) { Text("Open link") } } },
            dismissButton = { TextButton(onClick = viewModel::dismissAuthNotice) { Text("Close") } },
        )
    }
    uiState.hostKeyChallenge?.let { challenge ->
        val promptHostId = uiState.challengeHostId
        AlertDialog(
            onDismissRequest = {
                viewModel.answerHostKeyChallenge(promptHostId, challenge.id, false)
            },
            title = { Text("Verify SSH server") },
            text = {
                Text(
                    "${uiState.challengeHost}: Check this SSH fingerprint with the server administrator before you trust it.\n\n${challenge.message}",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.answerHostKeyChallenge(promptHostId, challenge.id, true)
                }) {
                    Text("Trust key")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.answerHostKeyChallenge(promptHostId, challenge.id, false)
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun HostKeyManager(
    keys: List<HostKeyEntry>,
    onForget: (HostKeyEntry) -> Unit,
    onClose: () -> Unit,
) {
    var confirm by remember { mutableStateOf<HostKeyEntry?>(null) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Saved host keys") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (keys.isEmpty()) Text("No host keys are saved on this phone.")
                keys.forEach { key ->
                    Text(key.label, style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
                    Text("${key.type}\n${key.fingerprint}", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { confirm = key }) { Text("Forget") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
    confirm?.let { key ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Forget this key?") },
            text = { Text("The next connection to ${key.label} asks you to check and trust its key again. Do this only if you know the server key changed.") },
            confirmButton = {
                TextButton(onClick = {
                    onForget(key)
                    confirm = null
                }) { Text("Forget key") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

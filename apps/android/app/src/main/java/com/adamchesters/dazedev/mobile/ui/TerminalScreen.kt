package com.adamchesters.dazedev.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.adamchesters.dazedev.mobile.ui.theme.TerminalBackground
import com.adamchesters.dazedev.mobile.ui.theme.TerminalForeground
import com.adamchesters.dazedev.mobile.ui.theme.TerminalMuted
import com.adamchesters.dazedev.mobile.voice.VoiceInputController
import com.adamchesters.dazedev.mobile.voice.VoiceInputPhase
import com.adamchesters.dazedev.mobile.voice.VoiceInputStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

fun DazeDevMobileUiState.terminalScreenState() =
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    uiState: TerminalScreenState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onSettings: () -> Unit,
    onSwitchTerminal: () -> Unit,
    onSelectTab: (com.adamchesters.dazedev.mobile.host.TerminalRow) -> Unit = {},
    onWindows: () -> Unit,
    onPrepareTarget: suspend () -> String,
    onValidateTarget: suspend (String) -> Boolean,
    onMessage: (String) -> Unit,
    onReconnect: () -> Unit = onRefresh,
    onMeasured: (Long, Int, Int) -> Unit = { _, _, _ -> },
) {
    val connected =
        uiState.terminal
            ?.state
            ?.collectAsState()
            ?.value == true
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val draftKey = "${uiState.selectedTargetHost}:${uiState.selectedSessionId}:${uiState.selectedStamp}"
    val drafts = remember { context.getSharedPreferences("terminal_drafts", android.content.Context.MODE_PRIVATE) }
    var input by rememberSaveable(draftKey) { mutableStateOf(drafts.getString(draftKey, "").orEmpty()) }
    LaunchedEffect(input, draftKey) {
        delay(250)
        val edit = drafts.edit()
        if (input.isBlank()) edit.remove(draftKey) else edit.putString(draftKey, input).putLong("saved:$draftKey", System.currentTimeMillis())
        edit.apply()
    }
    LaunchedEffect(draftKey) {
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val edit = drafts.edit()
            drafts.all.keys.filter { it.startsWith("original:") }.forEach { key ->
                val old = key.removePrefix("original:")
                if (!drafts.contains("saved:$old")) edit.putLong("saved:$old", System.currentTimeMillis())
            }
            drafts.all.filter { it.key.startsWith("saved:") && it.key != "saved:$draftKey" && (it.value as? Long ?: Long.MAX_VALUE) < cutoff }.forEach { (key, _) ->
                val old = key.removePrefix("saved:")
                edit
                    .remove(old)
                    .remove("original:$old")
                    .remove("target:$old")
                    .remove(key)
            }
            edit.apply()
        }
    }
    // The shared voice model stays loaded while this screen is open.
    DisposableEffect(Unit) {
        onDispose {
            Thread {
                com.adamchesters.dazedev.mobile.voice.VoiceModel
                    .release()
            }.start()
        }
    }

    var voiceStatus by remember(draftKey) { mutableStateOf(VoiceInputStatus()) }
    var voicePrefix by remember(draftKey) { mutableStateOf("") }
    var frozenTarget by rememberSaveable(draftKey) { mutableStateOf(drafts.getString("target:$draftKey", null)) }
    val scope = rememberCoroutineScope()
    val terminalController = remember(uiState.terminalEpoch) { TerminalPageController() }
    var submitting by remember(draftKey) { mutableStateOf(false) }
    var startingVoice by remember(draftKey) { mutableStateOf(false) }
    var voiceStartRequest by remember(draftKey) { mutableStateOf(0L) }
    var voiceStartJob by remember(draftKey) { mutableStateOf<Job?>(null) }

    var showKeys by remember(uiState.terminalEpoch) { mutableStateOf(false) }
    var voiceConsent by remember { mutableStateOf(false) }
    var showDraft by rememberSaveable(draftKey) { mutableStateOf(false) }
    var draftMessage by remember(draftKey) { mutableStateOf("") }

    fun reportDraft(message: String) {
        draftMessage = message
        onMessage(message)
    }
    val terminalScroll = rememberScrollState()
    val terminalHorizontalScroll = rememberScrollState()

    val voiceController =
        remember(draftKey) {
            VoiceInputController(
                context = context.applicationContext,
                onStatusChanged = { voiceStatus = it },
                onTranscript = { transcript, _ ->
                    drafts
                        .edit()
                        .putString("original:$draftKey", transcript)
                        .putLong("saved:$draftKey", System.currentTimeMillis())
                        .apply()
                    input =
                        listOf(voicePrefix, transcript)
                            .filter(String::isNotBlank)
                            .joinToString(" ")
                },
                onError = ::reportDraft,
            )
        }

    var permissionController by remember { mutableStateOf<VoiceInputController?>(null) }
    val microphonePermission =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                reportDraft("Microphone permission is required for voice input.")
            } else if (permissionController === voiceController && showDraft && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                voiceController.start()
            }
            permissionController = null
        }

    suspend fun startVoice(request: Long) {
        val target = onPrepareTarget()
        if (request != voiceStartRequest || !showDraft || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        frozenTarget = target
        drafts.edit().putString("target:$draftKey", frozenTarget).apply()
        voicePrefix = input.trim()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceController.start()
        } else {
            permissionController = voiceController
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun cancelVoiceStart() {
        voiceStartRequest += 1
        voiceStartJob?.cancel()
        voiceStartJob = null
        startingVoice = false
        permissionController = null
        voiceConsent = false
    }

    fun requestVoiceStart(downloadApproved: Boolean = false) {
        if (voiceStatus.isActive || startingVoice || submitting) return
        voiceStartRequest += 1
        val request = voiceStartRequest
        startingVoice = true
        voiceStartJob =
            scope.launch {
                try {
                    if (!voiceController.isAvailable) {
                        voiceController.start()
                    } else if (downloadApproved || voiceController.hasPreparedModel()) {
                        startVoice(request)
                    } else if (request == voiceStartRequest && showDraft) {
                        voiceConsent = true
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    reportDraft(e.message ?: "Voice input could not start.")
                } finally {
                    if (request == voiceStartRequest) startingVoice = false
                }
            }
    }

    fun toggleVoice() {
        if (voiceStatus.isActive) voiceController.stop() else requestVoiceStart()
    }

    fun enterDraft() {
        if (submitting || startingVoice || !connected || !terminalController.ready) return
        draftMessage = ""
        submitting = true
        scope.launch {
            try {
                if (voiceStatus.isActive) {
                    voiceController.stop()
                    if (withTimeoutOrNull(7_000) { while (voiceStatus.isActive) delay(25) } == null) {
                        reportDraft("Voice input is still finishing. Your draft is saved; tap Enter again in a moment.")
                        return@launch
                    }
                }
                val text = input
                if (text.isBlank()) {
                    reportDraft("No text to enter. Speak again or type your prompt.")
                    return@launch
                }
                val token = frozenTarget ?: onPrepareTarget()
                if (!onValidateTarget(token)) {
                    reportDraft("The recording target changed or disconnected. Your draft is saved. Review before choosing the current terminal.")
                    return@launch
                }
                val sent = terminalController.submit(text)
                if (sent == null) {
                    reportDraft("Submission was not confirmed. Check the terminal before retrying; your draft is saved.")
                } else if (sent) {
                    input = ""
                    voicePrefix = ""
                    frozenTarget = null
                    drafts
                        .edit()
                        .remove(draftKey)
                        .remove("target:$draftKey")
                        .apply()
                    showDraft = false
                    reportDraft("Entered in the terminal.")
                } else {
                    reportDraft("Draft was not entered. Wait for the terminal, or reduce it to 64 KiB. Your draft is saved.")
                }
            } catch (e: TimeoutCancellationException) {
                reportDraft("The host did not answer in time. Your draft is saved; try again.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportDraft(e.message?.let { "Draft was not entered: $it Your draft is saved." } ?: "Submission was not confirmed. Check the terminal before retrying; your draft is saved.")
            } finally {
                submitting = false
            }
        }
    }

    DisposableEffect(voiceController, lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    cancelVoiceStart()
                    voiceController.cancelCapture()
                    terminalController.clear()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelVoiceStart()
            voiceController.destroy()
        }
    }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .imePadding(),
    ) {
        TopAppBar(
            windowInsets = WindowInsets(0, 0, 0, 0),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to sessions")
                }
            },
            title = {
                TextButton(onClick = onSwitchTerminal) {
                    Column {
                        Text(
                            text = uiState.selectedSessionName.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                        Text(
                            text = "tmux on ${uiState.selectedTargetLabel} · switch",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(99.dp),
                        modifier =
                            Modifier.semantics {
                                contentDescription = if (connected) "Terminal connected" else "Terminal disconnected"
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Surface(
                                modifier = Modifier.size(7.dp),
                                color = MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ) {}
                            Text(
                                text = if (connected) "Live" else "Disconnected",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh terminal")
                    }
                }
            },
        )

        val tabs = uiState.tabs
        if (tabs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                tabs.forEach { tab ->
                    androidx.compose.material3.FilterChip(
                        selected = uiState.selectedPaneId == tab.paneId,
                        onClick = { onSelectTab(tab) },
                        enabled = !showDraft && voiceStatus.phase == VoiceInputPhase.Idle && !submitting && !startingVoice,
                        label = { Text(tab.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        Surface(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            color = TerminalBackground,
            shape = RoundedCornerShape(14.dp),
        ) {
            val stream = uiState.terminal
            var scrolledBack by remember(uiState.terminalEpoch) { mutableStateOf(false) }
            Box(Modifier.fillMaxSize()) {
                // A fresh WebView per attachment; it measures before the stream attaches.
                key(uiState.terminalEpoch) {
                    InteractiveTerminal(stream, terminalController, { cols, rows -> onMeasured(uiState.terminalEpoch, cols, rows) }, { scrolledBack = it }, onMessage, {
                        onMessage("The terminal view stopped. Reconnecting.")
                        onReconnect()
                    }, Modifier.fillMaxSize())
                }
                val problem = uiState.terminalError ?: if (stream != null && !connected) "Terminal disconnected." else null
                if (problem != null) {
                    Surface(Modifier.fillMaxSize(), color = TerminalBackground.copy(alpha = 0.92f)) {
                        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(problem, color = TerminalForeground, style = MaterialTheme.typography.bodyLarge)
                            Spacer(Modifier.size(12.dp))
                            if (!uiState.staleSession) Button(onClick = onReconnect) { Text("Reconnect") }
                        }
                    }
                } else if (stream == null) {
                    Box(Modifier.fillMaxSize().background(TerminalBackground), contentAlignment = Alignment.Center) { Text("Connecting terminal…", color = TerminalForeground) }
                } else if (scrolledBack) {
                    FilledTonalButton(
                        onClick = { terminalController.call("bottom") },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) { Text("Latest output") }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { terminalController.call("bottom") }) { Text("Latest output") }
                TextButton(onClick = {
                    showKeys = !showKeys
                    terminalController.call("keys", showKeys.toString())
                }) { Text(if (showKeys) "Hide keys" else "Keys") }
                TextButton(onClick = onWindows) { Text("Windows") }
                TextButton(onClick = onSettings) { Text("Settings") }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            frozenTarget = onPrepareTarget()
                            drafts.edit().putString("target:$draftKey", frozenTarget).apply()
                            onMessage("Draft now targets the current terminal.")
                        } catch (e: Exception) {
                            onMessage("Current target is unavailable.")
                        }
                    }
                }) { Text("Choose target") }
                TextButton(onClick = {
                    input = drafts.getString("original:$draftKey", input).orEmpty()
                    showDraft = true
                }) { Text("Restore transcript") }
                TextButton(onClick = {
                    cancelVoiceStart()
                    voiceController.cancelCapture()
                    voiceController.clearTranscript()
                    input = ""
                    voicePrefix = ""
                    frozenTarget = null
                    drafts
                        .edit()
                        .remove(draftKey)
                        .remove("original:$draftKey")
                        .remove("target:$draftKey")
                        .remove("saved:$draftKey")
                        .apply()
                }) { Text("Clear draft and transcript") }
                Text("Drafts and original transcripts stay on this device. Clear removes both; inactive entries expire after 7 days.")
                TextButton(onClick = { terminalController.call("selectAll") }) { Text("Select all") }
                TextButton(onClick = { terminalController.call("clearSelection") }) { Text("Clear selection") }
                TextButton(onClick = { terminalController.call("copy") }) { Text("Copy") }
                TextButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.primaryClip
                        ?.getItemAt(0)
                        ?.coerceToText(context)
                        ?.toString()
                        ?.let { if (!terminalController.paste(it)) onMessage("Clipboard was not pasted. Wait for the terminal, or choose text smaller than 64 KiB.") }
                }) { Text("Paste clipboard") }
            }
            FilledTonalButton(onClick = {
                showDraft = true
                draftMessage = ""
                requestVoiceStart()
            }, modifier = Modifier.heightIn(min = 56.dp)) {
                Icon(Icons.Rounded.Mic, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Voice")
            }
        }

        if (showDraft) {
            ModalBottomSheet(
                onDismissRequest = {
                    showDraft = false
                    cancelVoiceStart()
                    voiceController.stop()
                },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .imePadding()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Voice draft", style = MaterialTheme.typography.headlineSmall)
                            Text(uiState.selectedTargetLabel + " · " + uiState.selectedSessionName.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(99.dp)) {
                            Text("On device", Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    when (voiceStatus.phase) {
                        VoiceInputPhase.Loading -> {
                            val progress = voiceStatus.downloadProgress
                            Text(progress?.let { "Downloading Moonshine Medium: ${(it * 100).toInt()}%" } ?: "Loading voice model…", color = MaterialTheme.colorScheme.primary)
                            if (progress == null) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                            }
                        }

                        VoiceInputPhase.Listening -> {
                            Text("Listening. Tap End to edit, or Enter to send.", color = MaterialTheme.colorScheme.primary)
                        }

                        VoiceInputPhase.Stopping -> {
                            Text("Finishing your transcript…", color = MaterialTheme.colorScheme.primary)
                        }

                        VoiceInputPhase.Idle -> {
                            Unit
                        }
                    }
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text("Prompt") },
                        placeholder = { Text("Speak or type, then Enter") },
                        minLines = 3,
                        maxLines = 7,
                        readOnly = submitting || voiceStatus.phase == VoiceInputPhase.Stopping,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { enterDraft() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (draftMessage.isNotBlank()) Text(draftMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("End to edit, Enter to send", style = MaterialTheme.typography.labelLarge)
                            Text("Enter finishes listening, sends the prompt and returns to the terminal.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = ::enterDraft, enabled = (input.isNotBlank() || voiceStatus.phase == VoiceInputPhase.Listening) && connected && terminalController.ready && !submitting && !startingVoice && !uiState.isSending, modifier = Modifier.heightIn(min = 56.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
                            if (submitting) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                            } else {
                                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                                Spacer(Modifier.size(8.dp))
                                Text("Enter")
                            }
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FilledIconButton(
                                onClick = ::toggleVoice,
                                enabled = !submitting && !startingVoice && voiceStatus.phase != VoiceInputPhase.Stopping,
                                colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary),
                                modifier = Modifier.size(64.dp),
                            ) {
                                if (startingVoice) {
                                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                } else {
                                    Icon(if (voiceStatus.isActive) Icons.Rounded.Stop else Icons.Rounded.Mic, contentDescription = if (voiceStatus.isActive) "End listening" else "Start voice input", modifier = Modifier.size(30.dp))
                                }
                            }
                            Text(if (voiceStatus.isActive) "End" else "Voice", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text("Moonshine Medium runs on this phone. Installed models work offline.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(8.dp))
                }
            }
        }
    }

    if (voiceConsent) {
        AlertDialog(
            onDismissRequest = { voiceConsent = false },
            title = { Text("Prepare local voice") },
            text = { Text("Moonshine Medium needs a one-time download of about 269 MB. After that, voice works on this phone offline. Review your transcript, then tap Enter to submit it.") },
            confirmButton = {
                TextButton(onClick = {
                    voiceConsent = false
                    requestVoiceStart(downloadApproved = true)
                }) { Text("Download and listen") }
            },
            dismissButton = { TextButton(onClick = { voiceConsent = false }) { Text("Cancel") } },
        )
    }
}

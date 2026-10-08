package com.adamchesters.dazedev.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import com.adamchesters.dazedev.mobile.ui.theme.TerminalBackground
import com.adamchesters.dazedev.mobile.ui.theme.TerminalForeground
import com.adamchesters.dazedev.mobile.ui.theme.TerminalMuted
import com.adamchesters.dazedev.mobile.voice.VoiceInputController
import com.adamchesters.dazedev.mobile.voice.VoiceInputPhase
import com.adamchesters.dazedev.mobile.voice.VoiceInputStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    uiState: DazeDevMobileUiState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSend: (String) -> Unit,
    onSettings: () -> Unit,
    onPrepareTarget: suspend () -> String,
    onValidateTarget: suspend (String) -> Boolean,
    onMessage: (String) -> Unit,
) {
    val connected = uiState.terminal?.state?.collectAsState()?.value == true
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val draftKey="${uiState.selectedTargetHost}:${uiState.selectedSessionId}:${uiState.selectedStamp}"
    val drafts=remember {context.getSharedPreferences("terminal_drafts",android.content.Context.MODE_PRIVATE)}
    var input by rememberSaveable(draftKey) { mutableStateOf(drafts.getString(draftKey,"").orEmpty()) }
    LaunchedEffect(input,draftKey){drafts.edit().putString(draftKey,input).apply()}

    var voiceStatus by remember { mutableStateOf(VoiceInputStatus()) }
    var voicePrefix by remember { mutableStateOf("") }
    var frozenTarget by rememberSaveable(draftKey){mutableStateOf(drafts.getString("target:$draftKey",null))}
    val scope=rememberCoroutineScope()
    val terminalController = remember(uiState.terminalEpoch) { TerminalController() }
    fun pasteDraft() {
        scope.launch {
            try {
                val token=frozenTarget?:onPrepareTarget()
                if(!onValidateTarget(token)) {onMessage("The recording target changed or disconnected. Your draft is saved. Review before choosing the current terminal.");return@launch}
                terminalController.paste(input)
            }catch(e:Exception){onMessage(e.message?:"Draft remains saved; input was not sent.")}
        }
    }

    var showKeys by remember(uiState.terminalEpoch) { mutableStateOf(false) }
    var voiceConsent by remember { mutableStateOf(false) }
    var showDraft by rememberSaveable(draftKey) {mutableStateOf(false)}
    val terminalScroll = rememberScrollState()
    val terminalHorizontalScroll = rememberScrollState()

    val voiceController = remember(uiState.selectedSessionId,uiState.selectedTargetHost) {
        VoiceInputController(
            context = context.applicationContext,
            onStatusChanged = { voiceStatus = it },
            onTranscript = { transcript, _ ->
                drafts.edit().putString("original:$draftKey",transcript).apply()
                input = listOf(voicePrefix, transcript)
                    .filter(String::isNotBlank)
                    .joinToString(" ")
            },
            onError = onMessage,
        )
    }

    val microphonePermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            onMessage("Microphone permission is required for voice input.")
        } else if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            voiceController.start()
        }
    }

    DisposableEffect(voiceController, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {voiceController.stop();terminalController.clear()}
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            voiceController.destroy()
        }
    }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .imePadding(),
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to sessions")
                }
            },
            title = {
                Column {
                    Text(
                        text = uiState.selectedSessionName.orEmpty(),
                        maxLines=1,overflow=TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Text(
                        text = "tmux on ${uiState.selectedTargetLabel}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        modifier = Modifier.semantics {
                            contentDescription = if(connected) "Terminal connected" else "Terminal disconnected"
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
                                text = if(connected) "Live" else "Disconnected",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    TextButton(onClick={showDraft=true}){Text("Draft / voice")}
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh terminal")
                    }
                }
            },
        )

        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            color = TerminalBackground,
            shape = RoundedCornerShape(14.dp),
        ) {
            val stream = uiState.terminal
            if (stream == null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Text("Connecting terminal…",color=TerminalForeground)}
            else InteractiveTerminal(stream,terminalController,onMessage,Modifier.fillMaxSize())
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick=onSettings){Text("tmux settings")}
            TextButton(onClick={showKeys=!showKeys;terminalController.call("keys",showKeys.toString())}) {Text(if(showKeys)"Hide keys" else "Keys")}
            TextButton(onClick={scope.launch {try {frozenTarget=onPrepareTarget();drafts.edit().putString("target:$draftKey",frozenTarget).apply();onMessage("Draft now targets the current terminal.")}catch(e:Exception){onMessage("Current target is unavailable.")}}}){Text("Choose current target")}
            TextButton(onClick={input=drafts.getString("original:$draftKey",input).orEmpty();showDraft=true}){Text("Restore transcript")}
            TextButton(onClick={terminalController.call("selectAll")}){Text("Select all")}
            TextButton(onClick={terminalController.call("clearSelection")}){Text("Clear selection")}
            TextButton(onClick={terminalController.call("bottom")}){Text("Latest output")}
            TextButton(onClick={terminalController.call("copy")}){Text("Copy selection")}
            TextButton(onClick={
                val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let {terminalController.paste(it)}
            }){Text("Paste")}
        }

        if(showDraft) ModalBottomSheet(onDismissRequest={showDraft=false;voiceController.stop()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (voiceStatus.phase) {
                VoiceInputPhase.Loading -> Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val progress = voiceStatus.downloadProgress
                    Text(
                        text = progress?.let {
                            "Downloading Moonshine Medium: ${(it * 100).toInt()}%"
                        } ?: "Preparing Moonshine Medium on this phone...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (progress == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                VoiceInputPhase.Listening -> Text(
                    text = "Listening on this phone. Pause freely, then tap stop.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                VoiceInputPhase.Stopping -> Text(
                    text = "Finishing the on-device transcript...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                VoiceInputPhase.Idle -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                FilledTonalIconButton(
                    onClick = {
                        if (voiceStatus.isActive) {
                            voiceController.stop()
                        } else {
                            voicePrefix = input.trim()
                            voiceConsent = true

                        }
                    },
                    enabled = voiceStatus.phase != VoiceInputPhase.Stopping,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = if (voiceStatus.isActive) Icons.Rounded.Stop else Icons.Rounded.Mic,
                        contentDescription = when (voiceStatus.phase) {
                            VoiceInputPhase.Loading -> "Cancel voice preparation"
                            VoiceInputPhase.Listening -> "Stop listening"
                            VoiceInputPhase.Stopping -> "Finishing voice input"
                            VoiceInputPhase.Idle -> "Start voice input"
                        },
                    )
                }

                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Draft to paste") },
                    placeholder = { Text("Speak or type") },
                    minLines = 1,
                    maxLines = 4,
                    readOnly = voiceStatus.phase == VoiceInputPhase.Stopping,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(
                        onSend = {
                            if (
                                input.isNotBlank() && connected && terminalController.ready && !uiState.isSending &&
                                voiceStatus.phase != VoiceInputPhase.Stopping
                            ) {
                                pasteDraft()
                            }
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                )

                Button(
                    onClick = {
                        pasteDraft()
                    },
                    enabled = input.isNotBlank() && connected && terminalController.ready &&
                        !uiState.isSending &&
                        voiceStatus.phase != VoiceInputPhase.Stopping,
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.heightIn(min = 56.dp),
                ) {
                    if (uiState.isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(19.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                        Spacer(Modifier.size(7.dp))
                        Text("Paste")
                    }
                }
            }
            Text(
                text = "Moonshine Medium runs on this phone. First voice use downloads about 269 MB; later use works offline.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Paste inserts this draft without pressing Enter. Use the terminal keys to run it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    }

    if (voiceConsent) AlertDialog(
        onDismissRequest={voiceConsent=false}, title={Text("Local voice input")},
        text={Text("Moonshine Medium runs on this phone. First use downloads about 269 MB. The transcript stays in this draft until you paste it; nothing is sent automatically.")},
        confirmButton={TextButton(onClick={
            voiceConsent=false
            scope.launch {try {
                frozenTarget=onPrepareTarget()
                drafts.edit().putString("target:$draftKey",frozenTarget).apply()
                if(ContextCompat.checkSelfPermission(context,Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)voiceController.start()
                else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }catch(e:Exception){onMessage(e.message?:"Recording target unavailable.")}}

        }){Text("Prepare and listen")}},
        dismissButton={TextButton(onClick={voiceConsent=false}){Text("Cancel")}}
    )

}

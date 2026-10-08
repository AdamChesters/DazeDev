package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamchesters.dazedev.mobile.host.SessionLaunch
import com.adamchesters.dazedev.mobile.host.AgentCapability
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
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
import com.adamchesters.dazedev.mobile.tmux.AgentKind
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
    onRenameSession: (String,String,String) -> Unit,
) {
    var renameTarget by remember {mutableStateOf<TmuxSession?>(null)}
    var renameText by rememberSaveable {mutableStateOf("")}
    var showNewSession by rememberSaveable { mutableStateOf(false) }
    var pendingId by rememberSaveable {mutableStateOf("")}
    var pendingStamp by rememberSaveable {mutableStateOf("")}
    var sessionPendingEnd by rememberSaveable { mutableStateOf<String?>(null) }

    Box(
        modifier = Modifier
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
                navigationIcon = { TextButton(onClick=onBack) {Text("Back")} },
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
                modifier = Modifier
                    .widthIn(max = 900.dp)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
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
                    text = "SSH connected. ${uiState.sessions.size} tmux ${if (uiState.sessions.size == 1) "session" else "sessions"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.sessions.isEmpty() && !uiState.isRefreshing) {
                EmptySessions(
                    modifier = Modifier
                        .weight(1f)
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .align(Alignment.CenterHorizontally)
                        .padding(32.dp),
                    onCreate = { showNewSession = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxSize()
                        .align(Alignment.CenterHorizontally),
                    contentPadding = PaddingValues(
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
                            onEnd = { sessionPendingEnd = session.name;pendingId=session.id;pendingStamp=session.stamp },
                            isEnding = uiState.endingSessionName == session.name,
                            endEnabled = uiState.endingSessionName == null,
                        )
                        Row {TextButton(onClick={onAdoptSession(session.name)}){Text("Add to workspaces")};TextButton(onClick={renameTarget=session;renameText=session.name}){Text("Rename")}}
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
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp),
            )
        }
    }

    renameTarget?.let {target->AlertDialog(onDismissRequest={renameTarget=null},title={Text("Rename session")},text={OutlinedTextField(renameText,{renameText=it},label={Text("Name")},singleLine=true)},confirmButton={TextButton(onClick={onRenameSession(target.id,target.stamp,renameText);renameTarget=null},enabled=renameText.matches(Regex("[\\w-]+"))){Text("Rename")}},dismissButton={TextButton(onClick={renameTarget=null}){Text("Cancel")}})}
    if (showNewSession) {
        NewSessionDialog(
            defaultDirectory = uiState.profile.workingDirectory,
            isCreating = uiState.isCreating,
            repositories = uiState.repositories,
            agents = uiState.agents,
            folders = uiState.folders,
            onDismiss = { showNewSession = false },
            onCreate = { launch -> showNewSession=false;onCreateSession(launch) },
        )
    }

    sessionPendingEnd?.let { sessionName ->
        EndSessionDialog(
            sessionName = sessionName,
            attachedClients = uiState.sessions
                .firstOrNull { it.name == sessionName }
                ?.attachedClients
                ?: 0,
            onDismiss = { sessionPendingEnd = null },
            onConfirm = {
                sessionPendingEnd = null
                onEndSession(pendingId,pendingStamp,sessionName)
            },
        )
    }
}

@Composable
private fun SessionCard(
    session: TmuxSession,
    onClick: () -> Unit,
    onEnd: () -> Unit,
    isEnding: Boolean,
    endEnabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        enabled = !isEnding,
                        onClickLabel = "Open ${session.name} session",
                        role = Role.Button,
                        onClick = onClick,
                    )
                    .padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    modifier = Modifier.size(46.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.Terminal,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = session.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (session.attachedClients > 0) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(99.dp),
                            ) {
                                Text(
                                    text = "Attached",
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                    Text(
                        text = session.currentCommand.ifBlank { "shell" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${session.windowCount} ${if (session.windowCount == 1) "window" else "windows"}  •  ${activityText(session.lastActivityEpochSeconds)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (isEnding) {
                Box(
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(48.dp)
                        .semantics {
                            contentDescription = "Ending ${session.name} tmux session"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                IconButton(
                    onClick = onEnd,
                    enabled = endEnabled,
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "End ${session.name} tmux session",
                        tint = if (endEnabled) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun EndSessionDialog(
    sessionName: String,
    attachedClients: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val attachedNotice = when (attachedClients) {
        0 -> ""
        1 -> "\n\n1 client is currently attached and will be disconnected."
        else -> "\n\n$attachedClients clients are currently attached and will be disconnected."
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("End tmux session?") },
        text = {
            Text(
                "Ending \"$sessionName\" will stop every program running inside it. " +
                    "This cannot be undone.$attachedNotice",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("End session")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EmptySessions(modifier: Modifier = Modifier, onCreate: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.Computer,
            contentDescription = null,
            modifier = Modifier.size(54.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("No tmux sessions", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Start a shell or an installed agent in a persistent session.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onCreate, modifier = Modifier.height(50.dp)) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("New session")
        }
    }
}

@Composable
private fun NewSessionDialog(defaultDirectory:String,isCreating:Boolean,repositories:List<String>,agents:List<AgentCapability>,folders:List<WorkspaceFolder>,onDismiss:()->Unit,onCreate:(SessionLaunch)->Unit) {
    var workspace by rememberSaveable {mutableStateOf("")}
    var workspaceName by rememberSaveable {mutableStateOf("")}
    var count by rememberSaveable {mutableStateOf("1")}
    var step by rememberSaveable {mutableStateOf(0)}
    var name by rememberSaveable {mutableStateOf("")}
    var path by rememberSaveable {mutableStateOf(defaultDirectory)}
    var agent by rememberSaveable {mutableStateOf("shell")}
    var model by rememberSaveable {mutableStateOf("")}
    var effort by rememberSaveable {mutableStateOf("")}
    var identity by rememberSaveable {mutableStateOf("")}
    var brief by rememberSaveable {mutableStateOf("")}
    val capability=agents.firstOrNull {it.id==agent}
    Dialog(onDismissRequest=onDismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("New terminal · ${step+1}/3",style=MaterialTheme.typography.headlineSmall)
                when(step) {
                    0->{
                        OutlinedTextField(name,{name=it},label={Text("Session name")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(path,{path=it},label={Text("Working directory")},supportingText={Text("Absolute path, or blank for your home directory")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(workspaceName,{workspaceName=it},label={Text("New workspace name (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(count,{count=it},label={Text("Terminal count (1–8)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        FilterChip(selected=workspace.isBlank(),onClick={workspace=""},label={Text("New workspace")})
                        folders.forEach {folder->FilterChip(selected=workspace==folder.id,onClick={workspace=folder.id},label={Text("Add to ${folder.name}")})}
                        repositories.forEach {repo->TextButton(onClick={path=repo}){Text(repo)}}
                    }
                    1->{
                        (listOf("shell")+agents.map {it.id}).forEach {id->FilterChip(selected=agent==id,onClick={agent=id;model="";effort=""},label={Text(id)})}
                        capability?.let {Text(it.version)}
                        if(capability?.modelFlag==true){
                            Text("Model (blank uses agent default)")
                            OutlinedTextField(model,{model=it;effort=""},label={Text("Model")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            capability.models.forEach {id->TextButton(onClick={model=id;effort=""}){Text(id)}}
                        }
                        if(capability?.effortFlag==true){
                            OutlinedTextField(effort,{effort=it},label={Text("Effort (optional)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                            capability.efforts[model].orEmpty().forEach {value->TextButton(onClick={effort=value}){Text(value)}}
                        }
                        OutlinedTextField(identity,{identity=it},label={Text("Host GitHub account (optional)")},supportingText={Text("Use an account already signed in on this host. Credentials stay on the host.")},singleLine=true,modifier=Modifier.fillMaxWidth())
                        if(agent!="shell")OutlinedTextField(brief,{brief=it},label={Text("Starting brief (optional)")},minLines=3,modifier=Modifier.fillMaxWidth())
                    }
                    else->{Text("${count} terminals · ${workspaceName.ifBlank {name}}\n$name\n${path.ifBlank {"Home directory"}}\n$agent ${model.ifBlank {"default model"}} ${effort.ifBlank {"default effort"}}\n${identity.ifBlank {"Default host identity"}}");if(brief.isNotBlank())Text(brief);Text("Start opens this command on the selected host. No agent installation is performed.")}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={if(step>0)step-- else onDismiss()}){Text(if(step>0)"Back" else "Cancel")}
                    Button(onClick={if(step<2)step++ else onCreate(SessionLaunch(name,path,agent,model,effort,identity,brief,workspaceId=workspace,workspaceName=workspaceName.ifBlank {name},count=count.toIntOrNull()?:1))},enabled=!isCreating && name.matches(Regex("[\\w-]+")) && (path.isBlank()||path.startsWith('/')) && (count.toIntOrNull()?:0) in 1..8){Text(if(step<2)"Next" else "Start")}
                }
            }
        }
    }
}

private fun activityText(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "activity unknown"
    val seconds = max(0, Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).seconds)
    return when {
        seconds < 60 -> "active now"
        seconds < 3_600 -> "active ${seconds / 60} min ago"
        seconds < 86_400 -> "active ${seconds / 3_600} hr ago"
        else -> "active ${seconds / 86_400} days ago"
    }
}

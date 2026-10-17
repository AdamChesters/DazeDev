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

@Composable
internal fun NewSessionDialog(
    defaultDirectory: String,
    isCreating: Boolean,
    repositories: List<String>,
    agents: List<AgentCapability>,
    folders: List<WorkspaceFolder>,
    onDismiss: () -> Unit,
    onCreate: (SessionLaunch) -> Unit,
) {
    var workspace by rememberSaveable { mutableStateOf("") }
    var workspaceName by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("1") }
    var step by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf("") }
    var path by rememberSaveable { mutableStateOf(defaultDirectory) }
    var agent by rememberSaveable { mutableStateOf("shell") }
    var model by rememberSaveable { mutableStateOf("") }
    var effort by rememberSaveable { mutableStateOf("") }
    var identity by rememberSaveable { mutableStateOf("") }
    var brief by rememberSaveable { mutableStateOf("") }
    val capability = agents.firstOrNull { it.id == agent }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New terminal · ${step + 1}/3", style = MaterialTheme.typography.headlineSmall)
                when (step) {
                    0 -> {
                        OutlinedTextField(name, { name = it }, label = { Text("Session name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(path, { path = it }, label = { Text("Working directory") }, supportingText = { Text("Absolute path, or blank for your home directory") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(workspaceName, { workspaceName = it }, label = { Text("New workspace name (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(count, { count = it }, label = { Text("Terminal count (1–8)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        FilterChip(selected = workspace.isBlank(), onClick = { workspace = "" }, label = { Text("New workspace") })
                        folders.forEach { folder -> FilterChip(selected = workspace == folder.id, onClick = { workspace = folder.id }, label = { Text("Add to ${folder.name}") }) }
                        repositories.forEach { repo -> TextButton(onClick = { path = repo }) { Text(repo) } }
                    }

                    1 -> {
                        (listOf("shell" to "shell") + agents.map { it.id to it.name }).forEach { (id, label) ->
                            FilterChip(selected = agent == id, onClick = {
                                agent = id
                                model = ""
                                effort = ""
                            }, label = { Text(label) })
                        }
                        capability?.let { Text(it.version) }
                        if (capability?.modelFlag == true) {
                            Text("Model (blank uses agent default)")
                            OutlinedTextField(model, {
                                model = it
                                effort = ""
                            }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            capability.models.forEach { id ->
                                TextButton(onClick = {
                                    model = id
                                    effort = ""
                                }) { Text(id) }
                            }
                        }
                        if (capability?.effortFlag == true) {
                            OutlinedTextField(effort, { effort = it }, label = { Text("Effort (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            capability.efforts[model].orEmpty().forEach { value -> TextButton(onClick = { effort = value }) { Text(value) } }
                        }
                        OutlinedTextField(identity, { identity = it }, label = { Text("Host GitHub account (optional)") }, supportingText = { Text("Use an account already signed in on this host. Credentials stay on the host.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (agent != "shell" && capability?.prompt != false) OutlinedTextField(brief, { brief = it }, label = { Text("Starting brief (optional)") }, minLines = 3, modifier = Modifier.fillMaxWidth())
                    }

                    else -> {
                        Text("$count terminals · ${workspaceName.ifBlank {name}}\n$name\n${path.ifBlank {"Home directory"}}\n${capability?.name ?: agent} ${model.ifBlank {"default model"}} ${effort.ifBlank {"default effort"}}\n${identity.ifBlank {"Default host identity"}}")
                        if (brief.isNotBlank() && capability?.prompt != false) Text(brief)
                        Text("Start opens this command on the selected host. No agent installation is performed.")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = { if (step > 0) step-- else onDismiss() }) { Text(if (step > 0) "Back" else "Cancel") }
                    Button(onClick = { if (step < 2) step++ else onCreate(SessionLaunch(name, path, agent, model, effort, identity, if (capability?.prompt == false) "" else brief, workspaceId = workspace, workspaceName = workspaceName.ifBlank { name }, count = count.toIntOrNull() ?: 1)) }, enabled = !isCreating && name.matches(Regex("[\\w-]+")) && (path.isBlank() || path.startsWith('/')) && (count.toIntOrNull() ?: 0) in 1..8) { Text(if (step < 2) "Next" else "Start") }
                }
            }
        }
    }
}

internal fun activityText(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "activity unknown"
    val seconds = max(0, Duration.between(Instant.ofEpochSecond(epochSeconds), Instant.now()).seconds)
    return when {
        seconds < 60 -> "active now"
        seconds < 3_600 -> "active ${seconds / 60} min ago"
        seconds < 86_400 -> "active ${seconds / 3_600} hr ago"
        else -> "active ${seconds / 86_400} days ago"
    }
}

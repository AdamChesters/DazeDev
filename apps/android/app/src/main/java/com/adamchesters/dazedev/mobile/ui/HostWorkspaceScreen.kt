package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.R
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
import kotlin.math.roundToInt

@Composable
fun HostWorkspaceScreen(
    state: DazeDevMobileUiState,
    padding: PaddingValues,
    viewModel: MainViewModel,
) {
    val context = LocalContext.current
    var discardDraft by remember(state.profile.id) { mutableStateOf(false) }
    var folderEdit by remember(state.profile.id) { mutableStateOf<WorkspaceFolder?>(null) }
    var folderName by remember(state.profile.id) { mutableStateOf("") }
    var folderRemove by remember(state.profile.id) { mutableStateOf<WorkspaceFolder?>(null) }
    var notices by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(false) }
    var creatingGroup by remember { mutableStateOf(false) }
    var groupName by remember { mutableStateOf("") }
    if (creatingGroup) {
        AlertDialog(onDismissRequest = { creatingGroup = false }, title = { Text("New hostless workspace") }, text = { OutlinedTextField(groupName, { groupName = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = {
            TextButton(onClick = {
                viewModel.createLocalGroup(groupName)
                creatingGroup = false
                groupName = ""
            }, enabled = groupName.isNotBlank()) { Text("Create") }
        }, dismissButton = { TextButton(onClick = { creatingGroup = false }) { Text("Cancel") } })
    }
    if (history) ReleaseHistoryDialog(state, viewModel) { history = false }
    if (discardDraft) {
        AlertDialog(onDismissRequest = { discardDraft = false }, title = { Text("Discard saved draft?") }, text = { Text("This removes unsaved workspace edits from this phone. Terminal sessions keep running.") }, confirmButton = {
            TextButton(onClick = {
                viewModel.discardDraft()
                discardDraft = false
            }) { Text("Discard draft") }
        }, dismissButton = { TextButton(onClick = { discardDraft = false }) { Text("Keep draft") } })
    }
    folderEdit?.let { folder ->
        AlertDialog(onDismissRequest = { folderEdit = null }, title = { Text("Rename workspace") }, text = { OutlinedTextField(folderName, { folderName = it }, label = { Text("Name") }, singleLine = true) }, confirmButton = {
            TextButton(onClick = {
                viewModel.editWorkspace(folder.id, name = folderName.trim())
                folderEdit = null
            }, enabled = folderName.isNotBlank()) { Text("Save") }
        }, dismissButton = { TextButton(onClick = { folderEdit = null }) { Text("Cancel") } })
    }
    folderRemove?.let { folder ->
        AlertDialog(onDismissRequest = { folderRemove = null }, title = { Text("Remove workspace?") }, text = { Text("Remove ${folder.name} from the shared profile. Terminal sessions keep running.") }, confirmButton = {
            TextButton(onClick = {
                viewModel.editWorkspace(folder.id, remove = true)
                folderRemove = null
            }) { Text("Remove workspace") }
        }, dismissButton = { TextButton(onClick = { folderRemove = null }) { Text("Cancel") } })
    }
    if (notices != null) AlertDialog(onDismissRequest = { notices = null }, title = { Text("Licenses and credits") }, text = { Text(notices.orEmpty(), modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = { notices = null }) { Text("Close") } })
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(painterResource(R.drawable.dazedev_logo), contentDescription = null, modifier = Modifier.size(48.dp))
            Column {
                Text("DazeDev", style = MaterialTheme.typography.headlineSmall)
                Text("Terminal workspaces", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        if (state.showHosts) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Hosts", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = viewModel::newHost) { Text("+ Add host") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.hosts.isEmpty()) item { Text("Add a Linux or macOS host running Python 3 and tmux to create your first workspace.", modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(state.hosts, key = { it.profile.id }) { host ->
                    OutlinedButton(onClick = { viewModel.selectHost(host.profile.id) }, shape = RoundedCornerShape(3.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(host.profile.name.ifBlank { host.profile.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host.profile.host, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(host.phase.name, style = MaterialTheme.typography.labelMedium)
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(top = 16.dp))
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Hostless workspaces", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { creatingGroup = true }) { Text("+ Add") }
                    }
                }
                items(state.localGroups, key = { "local:${it.id}" }) { group -> OutlinedButton(onClick = { viewModel.selectLocalGroup(group.id) }, shape = RoundedCornerShape(3.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(group.name, modifier = Modifier.fillMaxWidth()) } }
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::showHosts) { Text("‹ Hosts") }
                Text(state.profile.name.ifBlank { state.profile.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = viewModel::refreshSessions, enabled = !state.isRefreshing) { Text(if (state.isRefreshing) "Refreshing…" else "Refresh") }
                TextButton(onClick = viewModel::disconnect) { Text("Disconnect") }
            }
            if (state.conflict) {
                Text("Workspace changed on another device. Your unsaved draft is kept on this phone.", modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = viewModel::reloadProfile) { Text("Reload host profile; keep draft") }
            }
            if (state.recoveryDraft) {
                Row {
                    TextButton(onClick = viewModel::reviewDraft) { Text("Review saved draft") }
                    TextButton(onClick = { discardDraft = true }) { Text("Discard") }
                }
            }
            if (state.selectedFolder == null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Workspaces", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { viewModel.selectFolder("__sessions") }) { Text("+ Terminal") }
                }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                    if (state.folders.isEmpty()) item { Text("No workspaces yet. Discover or start a terminal to add one.", modifier = Modifier.padding(vertical = 16.dp)) }
                    items(state.folders, key = { it.id }) { folder ->
                        WorkspaceRow(
                            folder,
                            state.folders.indexOf(folder),
                            state.folders.size,
                            onChoose = { viewModel.selectFolder(folder.id) },
                            onRename = {
                                folderEdit = folder
                                folderName = folder.name
                            },
                            onRemove = { folderRemove = folder },
                            onMove = { viewModel.editWorkspace(folder.id, move = it) },
                            onColor = { viewModel.editWorkspace(folder.id, background = it) },
                        )
                        HorizontalDivider()
                    }
                }
            } else {
                val folder = state.folders.firstOrNull { it.id == state.selectedFolder }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.selectFolder(null) }) { Text("‹ Workspaces") }
                    Text(folder?.name.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                WorkspaceTerminals(state, folder, viewModel, Modifier.weight(1f))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { history = true }) { Text("Changelog") }
            TextButton(onClick = viewModel::openHostKeys) { Text("Host keys") }
            TextButton(onClick = { viewModel.setSecureScreen(!state.secureScreen) }, modifier = Modifier.semantics { contentDescription = if (state.secureScreen) "Private screen is on. Screenshots and the Recents preview are blocked." else "Private screen is off." }) { Text(if (state.secureScreen) "Private screen: on" else "Private screen: off") }
            TextButton(onClick = {
                val assets = context.assets
                val paths = listOf("moonshine-license.txt", "xterm-license.txt", "addon-fit-license.txt", "addon-unicode11-license.txt", "addon-web-links-license.txt") + assets.list("licenses").orEmpty().map { "licenses/$it" }
                notices = paths.joinToString("\n\n") { path -> assets.open(path).bufferedReader().use { it.readText() } }
            }) { Text("Licenses and credits") }
        }
    }
}

@Composable
private fun WorkspaceRow(
    folder: WorkspaceFolder,
    index: Int,
    count: Int,
    onChoose: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
    onColor: (String) -> Unit,
) {
    var menu by remember(folder.id) { mutableStateOf(false) }
    var dragging by remember(folder.id) { mutableStateOf(false) }
    var distance by remember(folder.id) { mutableFloatStateOf(0f) }
    var rowHeight by remember(folder.id) { mutableIntStateOf(1) }
    val move by rememberUpdatedState(onMove)
    val destination = (index + (distance / rowHeight).roundToInt()).coerceIn(0, count - 1)
    val background = runCatching { Color(android.graphics.Color.parseColor(folder.background)) }.getOrDefault(MaterialTheme.colorScheme.surface)
    val foreground = if (background.luminance() > 0.5f) Color.Black else Color.White
    Surface(color = if (dragging) MaterialTheme.colorScheme.secondaryContainer else background, contentColor = if (dragging) MaterialTheme.colorScheme.onSecondaryContainer else foreground, shape = RoundedCornerShape(0.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).onSizeChanged { if (!dragging) rowHeight = it.height.coerceAtLeast(1) }, verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .semantics { contentDescription = "Hold and drag to reorder ${folder.name}. Move up and down are also in its menu." }
                    .pointerInput(folder.id, index, count) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                distance = 0f
                                dragging = true
                            },
                            onDragCancel = {
                                dragging = false
                                distance = 0f
                            },
                            onDragEnd = {
                                val delta = (index + (distance / rowHeight).roundToInt()).coerceIn(0, count - 1) - index
                                dragging = false
                                distance = 0f
                                if (delta != 0) move(delta)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                distance += amount.y
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.DragHandle, contentDescription = null) }
            Column(Modifier.weight(1f).clickable(onClick = onChoose).padding(vertical = 16.dp)) {
                Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dragging) Text("Move to position ${destination + 1}", style = MaterialTheme.typography.labelSmall)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "${folder.name} options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = {
                        menu = false
                        onRename()
                    })
                    DropdownMenuItem(text = { Text("Move up") }, enabled = index > 0, onClick = {
                        menu = false
                        move(-1)
                    })
                    DropdownMenuItem(text = { Text("Move down") }, enabled = index < count - 1, onClick = {
                        menu = false
                        move(1)
                    })
                    DropdownMenuItem(text = { Text("Blue") }, onClick = {
                        menu = false
                        onColor("#1456a0")
                    })
                    DropdownMenuItem(text = { Text("Teal") }, onClick = {
                        menu = false
                        onColor("#006b62")
                    })
                    DropdownMenuItem(text = { Text("Remove workspace") }, onClick = {
                        menu = false
                        onRemove()
                    })
                }
            }
        }
    }
}

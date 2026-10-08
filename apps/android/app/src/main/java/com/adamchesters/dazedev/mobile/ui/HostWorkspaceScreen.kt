package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HostWorkspaceScreen(state:DazeDevMobileUiState,padding:PaddingValues,viewModel:MainViewModel) {
    val context=LocalContext.current
    var discardDraft by remember(state.profile.id){mutableStateOf(false)}
    var folderEdit by remember(state.profile.id) {mutableStateOf<WorkspaceFolder?>(null)}
    var folderName by remember(state.profile.id) {mutableStateOf("")}
    var folderRemove by remember(state.profile.id) {mutableStateOf<WorkspaceFolder?>(null)}
    var notices by remember {mutableStateOf<String?>(null)}
    if(discardDraft)AlertDialog(onDismissRequest={discardDraft=false},title={Text("Discard saved draft?")},text={Text("This removes the unsaved workspace edits from this phone. The host profile and terminal sessions keep running.")},confirmButton={TextButton(onClick={viewModel.discardDraft();discardDraft=false}){Text("Discard draft")}},dismissButton={TextButton(onClick={discardDraft=false}){Text("Keep draft")}})
    folderEdit?.let {folder->AlertDialog(onDismissRequest={folderEdit=null},title={Text("Rename workspace")},text={OutlinedTextField(folderName,{folderName=it},label={Text("Name")})},confirmButton={TextButton(onClick={viewModel.editWorkspace(folder.id,name=folderName);folderEdit=null},enabled=folderName.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick={folderEdit=null}){Text("Cancel")}})}
    folderRemove?.let {folder->AlertDialog(onDismissRequest={folderRemove=null},title={Text("Remove workspace?")},text={Text("Remove ${folder.name} from the shared profile. Its terminal sessions keep running.")},confirmButton={TextButton(onClick={viewModel.editWorkspace(folder.id,remove=true);folderRemove=null}){Text("Remove workspace")}},dismissButton={TextButton(onClick={folderRemove=null}){Text("Cancel")}})}
    if(notices!=null)AlertDialog(onDismissRequest={notices=null},title={Text("Licenses and credits")},text={Text(notices.orEmpty(),modifier=Modifier.verticalScroll(rememberScrollState()))},confirmButton={TextButton(onClick={notices=null}){Text("Close")}})
    Column(Modifier.fillMaxSize().padding(padding)) {
        TextButton(onClick={
            val assets=context.assets
            val paths=listOf("moonshine-license.txt","xterm-license.txt","addon-fit-license.txt","addon-unicode11-license.txt") + assets.list("licenses").orEmpty().map {"licenses/$it"}
            notices=paths.joinToString("\n\n") {path->assets.open(path).bufferedReader().use {it.readText()}}
        }){Text("Licenses and credits")}

        Text("DazeDev",style=MaterialTheme.typography.headlineMedium,modifier=Modifier.padding(16.dp))
        if(state.showHosts) {
            Button(onClick=viewModel::newHost,modifier=Modifier.padding(16.dp)){Text("Add SSH host")}
            if(state.hosts.isEmpty())Text("Connect a Linux or macOS host running Python 3 and tmux. Windows-local terminals need a separate bridge.",modifier=Modifier.padding(16.dp))
            LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.hosts,key={it.profile.id}) {h->
                    OutlinedButton(onClick={viewModel.selectHost(h.profile.id)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){
                        Column {Text(h.profile.name.ifBlank {h.profile.host});Text("${h.phase} · ${h.workspaceCount} workspaces")}
                    }
                }
            }
        } else {
            Row(Modifier.padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(onClick=viewModel::showHosts){Text("Hosts")}
                TextButton(onClick=viewModel::refreshSessions){Text("Refresh")}
                TextButton(onClick=viewModel::disconnect){Text("Disconnect")}
            }
            Text(state.profile.name.ifBlank {state.profile.host},style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(16.dp))
            if(state.conflict) {
                Text("Workspace changed on another device. Your unsaved draft is kept on this phone.",modifier=Modifier.padding(16.dp))
                TextButton(onClick=viewModel::reloadProfile){Text("Reload host profile; keep draft")}
            }
            if(state.recoveryDraft){TextButton(onClick=viewModel::reviewDraft){Text("Review and reapply saved draft")};TextButton(onClick={discardDraft=true}){Text("Discard saved draft")}}
            if(state.selectedFolder==null) {
                TextButton(onClick={viewModel.selectFolder("__sessions")},modifier=Modifier.padding(8.dp)){Text("Discover or start a terminal")}
                LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(state.folders,key={it.id}) {w->
                        Column {
                            val background=runCatching {Color(android.graphics.Color.parseColor(w.background))}.getOrDefault(MaterialTheme.colorScheme.surface)
                            val foreground=if(background.luminance()>0.5f)Color.Black else Color.White
                            OutlinedButton(onClick={viewModel.selectFolder(w.id)},colors=ButtonDefaults.outlinedButtonColors(containerColor=background,contentColor=foreground),modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Column {Text(w.name);Text("${w.rows.size} terminals")}}
                            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick={folderEdit=w;folderName=w.name}){Text("Rename")}
                                TextButton(onClick={viewModel.editWorkspace(w.id,move=-1)}){Text("Move up")}
                                TextButton(onClick={viewModel.editWorkspace(w.id,move=1)}){Text("Move down")}
                                TextButton(onClick={viewModel.editWorkspace(w.id,background="#1456a0")}){Text("Blue")}
                                TextButton(onClick={viewModel.editWorkspace(w.id,background="#006b62")}){Text("Teal")}
                                TextButton(onClick={folderRemove=w}){Text("Remove")}
                            }
                        }
                    }

                }
            } else {
                TextButton(onClick={viewModel.selectFolder(null)}){Text("Back to workspaces")}
                val folder=state.folders.firstOrNull {it.id==state.selectedFolder}
                Text(folder?.name.orEmpty(),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(16.dp))
                LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    items(folder?.rows.orEmpty(),key={it.paneId}) {row->OutlinedButton(onClick={viewModel.selectPane(row)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){
                        Column {Text(row.name);val target=state.hosts.firstOrNull {it.fingerprint==row.hostKey && row.hostKey.isNotBlank()};Text(target?.let {"${it.profile.name.ifBlank {it.profile.host}} · ${it.phase}"}?:"Connect the saved target host") }
                    }}
                }
            }
        }
    }
}

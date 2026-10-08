package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.adamchesters.dazedev.mobile.host.SharedProfile
import com.adamchesters.dazedev.mobile.host.TerminalRow
import org.json.JSONObject

@Composable
fun TerminalSwitcher(state:DazeDevMobileUiState,onClose:()->Unit,onChoose:(String,TerminalRow)->Unit) {
    var query by remember {mutableStateOf("")}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp)) {
            Text("Switch terminal",style=MaterialTheme.typography.headlineSmall)
            TextButton(onClick=onClose){Text("Close")}
            OutlinedTextField(query,{query=it},label={Text("Search host, workspace or terminal")},modifier=Modifier.fillMaxWidth())
            val entries=state.hosts.filter {it.phase==ConnectionPhase.Connected}.flatMap {host->host.folders.flatMap {folder->folder.rows.map {Triple(host,folder,it)}}}
                .filter {(host,folder,row)->listOf(host.profile.name,host.profile.host,folder.name,row.name,state.destination(row).label,state.destination(row).reference.address).any {it.contains(query,ignoreCase=true)}}
            LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=16.dp)) {
                items(entries,key={(host,folder,row)->"${host.profile.id}:${folder.id}:${row.paneId}"}) {(host,folder,row)->
                    TerminalHostCard(state,row,{onChoose(host.profile.id,row)},folder.name)
                }
                if(entries.isEmpty())item {Text("No matching terminals on connected hosts.")}
            }
        }}
    }
}

@Composable
fun TmuxWindowPicker(windows:List<TmuxWindow>,onChoose:(String)->Unit,onClose:()->Unit) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Text("tmux windows",style=MaterialTheme.typography.headlineSmall)
            Text("Choosing a window changes the active window for every client attached to this tmux session.")
            TextButton(onClick=onClose){Text("Close")}
            LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(windows,key={it.id}) {window->OutlinedButton(onClick={onChoose(window.id)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)){Text("${window.name}${if(window.active)" · active" else ""}")}}
            }
        }}
    }
}

@Composable
fun DraftReview(document:JSONObject,onApply:()->Unit,onClose:()->Unit) {
    val folders=remember(document){SharedProfile(document).folders()}
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Text("Review saved workspace draft",style=MaterialTheme.typography.headlineSmall)
            Text("Your edits have been combined with the current host profile. Conflicting changes are never overwritten. Applying saves these workspaces to the shared host; terminal sessions keep running.")
            Row {TextButton(onClick=onClose){Text("Keep draft")};Button(onClick=onApply){Text("Apply reviewed draft")}}
            LazyColumn {items(folders,key={it.id}) {folder->Text("${folder.name} · ${folder.rows.size} terminals",modifier=Modifier.padding(vertical=12.dp))}}
        }}
    }
}

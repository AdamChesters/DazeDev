package com.adamchesters.dazedev.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adamchesters.dazedev.mobile.host.*

@Composable
fun WorkspaceTerminals(state:DazeDevMobileUiState,folder:WorkspaceFolder?,viewModel:MainViewModel,modifier:Modifier=Modifier) {
    var grouping by remember(folder?.id){mutableStateOf(false)}
    var selected by remember(folder?.id){mutableStateOf(setOf<String>())}
    Column(modifier) {
        Row(Modifier.padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically) {
            if(grouping) {
                TextButton(onClick={viewModel.groupTerminalTabs(selected.toList());grouping=false;selected=emptySet()},enabled=selected.size>=2){Text("Group ${selected.size} tabs")}
                TextButton(onClick={grouping=false;selected=emptySet()}){Text("Cancel")}
            } else if((folder?.rows?.size?:0)>1)TextButton(onClick={grouping=true}){Text("Group terminals as tabs")}
        }
        LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(folder?.rows.isNullOrEmpty())item {Text(if(state.localGroupId!=null)"This is a hostless workspace. Connect to a host and open its terminals, then use Add terminal views. On desktop, hold Ctrl and drag terminal windows into a hostless group. The originals stay in place." else "This workspace has no terminals.",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            items(folder?.rows.orEmpty(),key={it.paneId}) {row->
                val group=folder?.tabGroups?.firstOrNull {row.paneId in it.panes}
                var menu by remember(row.paneId){mutableStateOf(false)}
                Column {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        if(grouping)Checkbox(checked=row.paneId in selected,onCheckedChange={checked->selected=if(checked)selected+row.paneId else selected-row.paneId})
                        Box(Modifier.weight(1f)){TerminalHostCard(state,row,{if(grouping)selected=if(row.paneId in selected)selected-row.paneId else selected+row.paneId else viewModel.selectPane(row)},if(group==null)"" else "Tab group ${folder!!.tabGroups.indexOf(group)+1}")}
                        if(!grouping && (group!=null || state.localGroupId!=null))Box {
                            IconButton(onClick={menu=true}){Icon(Icons.Default.MoreVert,contentDescription="${row.name} options")}
                            DropdownMenu(expanded=menu,onDismissRequest={menu=false}) {
                                if(group!=null)DropdownMenuItem(text={Text("Move out of tab group")},onClick={menu=false;viewModel.ungroupTerminalTab(row.paneId)})
                                if(state.localGroupId!=null)DropdownMenuItem(text={Text("Remove view from this group")},onClick={menu=false;viewModel.removeLocalTerminalView(row.paneId)})
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LocalWorkspaceScreen(state:DazeDevMobileUiState,padding:PaddingValues,viewModel:MainViewModel) {
    val folder=state.currentFolder()
    var adding by remember {mutableStateOf(false)}
    var renaming by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var name by remember(folder?.id){mutableStateOf(folder?.name.orEmpty())}
    Column(Modifier.fillMaxSize().padding(padding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){TextButton(onClick=viewModel::showHosts){Text("‹ Hosts")};Text(folder?.name.orEmpty(),style=MaterialTheme.typography.titleLarge)}
        Text("Hostless workspace · saved on this phone",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){TextButton(onClick={adding=true}){Text("Add terminal views")};TextButton(onClick={renaming=true}){Text("Rename")};TextButton(onClick={deleting=true}){Text("Remove")}}
        val needed=state.groupHosts()
        if(needed.any {it.host!=null})TextButton(onClick=viewModel::connectGroupHosts,modifier=Modifier.align(Alignment.CenterHorizontally)){Text("Connect to ${needed.count {it.host!=null}} saved hosts")}
        if(needed.any {it.host==null})Text("Some hosts need setup. Tap their terminal view to enter connection details.",modifier=Modifier.padding(horizontal=16.dp),style=MaterialTheme.typography.bodySmall)
        WorkspaceTerminals(state,folder,viewModel,Modifier.weight(1f))
    }
    if(adding)TerminalSwitcher(state,{adding=false}) {host,row->
        val source=state.hosts.firstOrNull {it.profile.id==host}?.folders?.firstOrNull {it.rows.any {r->r.paneId==row.paneId}}
        if(source!=null)viewModel.cloneToLocalGroup(host,source.id,row.paneId)
        adding=false
    }
    if(renaming)AlertDialog(onDismissRequest={renaming=false},title={Text("Rename workspace")},text={OutlinedTextField(name,{name=it},label={Text("Name")},singleLine=true)},confirmButton={TextButton(onClick={viewModel.editLocalGroup(name=name.trim());renaming=false},enabled=name.isNotBlank()){Text("Save")}},dismissButton={TextButton(onClick={renaming=false}){Text("Cancel")}})
    if(deleting)AlertDialog(onDismissRequest={deleting=false},title={Text("Remove this group?")},text={Text("Remove these terminal views from this phone. Original workspaces and sessions stay in place.")},confirmButton={TextButton(onClick={viewModel.editLocalGroup(remove=true);deleting=false}){Text("Remove group")}},dismissButton={TextButton(onClick={deleting=false}){Text("Cancel")}})
}

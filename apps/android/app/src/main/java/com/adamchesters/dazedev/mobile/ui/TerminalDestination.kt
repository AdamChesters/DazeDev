package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.HostReference
import com.adamchesters.dazedev.mobile.host.TerminalRow

data class TerminalDestination(val row:TerminalRow, val host:HostSummary?, val reference:HostReference, val otherHost:Boolean) {
    val connected:Boolean get()=host?.phase==ConnectionPhase.Connected
    val label:String get()=reference.name.ifBlank {reference.address.ifBlank {"Unconfigured host"}}
    val status:String get()=when {connected->"Connected";host?.phase==ConnectionPhase.Connecting->"Connecting";host!=null->"Saved · reconnect";else->"Not set up on this phone"}
    val action:String get()=when {connected->"Open";host!=null->"Connect";else->"Set up host"}
    fun profile():ConnectionProfile = host?.profile ?: reference.connectionProfile()
}

fun DazeDevMobileUiState.destination(row:TerminalRow):TerminalDestination {
    val matches=hosts.filter {row.hostKey.isNotBlank() && it.fingerprint==row.hostKey}
    val host=matches.firstOrNull {it.phase==ConnectionPhase.Connected} ?: matches.firstOrNull()
    val identity=row.hostKey.split(':',limit=3)
    val reference=host?.profile?.let(HostReference::fromProfile) ?: row.hostDetails ?: HostReference(
        user=identity.getOrNull(1).orEmpty(),socket=identity.getOrNull(2).orEmpty(),
    )
    return TerminalDestination(row,host,reference,localGroupId!=null || host?.profile?.id!=profile.id)
}

fun DazeDevMobileUiState.groupHosts():List<TerminalDestination> = localGroups.firstOrNull {it.id==localGroupId}?.rows.orEmpty()
    .map {destination(it)}.filter { !it.connected && it.host?.phase!=ConnectionPhase.Connecting }.distinctBy {it.row.hostKey.ifBlank {it.row.hostId}}

fun DazeDevMobileUiState.currentFolder()=if(localGroupId!=null)localGroups.firstOrNull {it.id==localGroupId}else folders.firstOrNull {it.id==selectedFolder}

fun DazeDevMobileUiState.terminalTabs():List<TerminalRow> {
    val folder=currentFolder()?:return emptyList()
    val pane=selectedPaneId?:return emptyList()
    val group=folder.tabGroups.firstOrNull {pane in it.panes}?:return emptyList()
    return group.panes.mapNotNull {id->folder.rows.firstOrNull {it.paneId==id}}
}

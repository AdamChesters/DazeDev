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
    return TerminalDestination(row,host,reference,host?.profile?.id!=profile.id)
}

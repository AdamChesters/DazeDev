package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.*
import com.adamchesters.dazedev.mobile.host.*
import org.junit.Assert.*
import org.junit.Test

class TerminalDestinationTest {
    private val owner=ConnectionProfile(host="owner",username="user",id="owner")
    private fun row(key:String="machine-b:user:other-socket",details:HostReference?=null)=TerminalRow("pane","Shell","$2","1:2:3","desktop-b",key,"",details)
    @Test fun legacyForeignHostOffersSetupWithoutInventingAddress() {
        val target=DazeDevMobileUiState(profile=owner).destination(row())
        assertEquals("Set up host",target.action)
        assertEquals("Not set up on this phone",target.status)
        assertTrue(target.otherHost)
        assertFalse(target.connected)
        assertEquals("",target.profile().host)
        assertEquals("user",target.profile().username)
        assertEquals("other-socket",target.profile().socket)
    }
    @Test fun freshPhoneUsesNonSecretTailscaleDetails() {
        val details=HostReference("Devbox","tailscale","devbox.example.ts.net","ubuntu",2222,"work")
        val target=DazeDevMobileUiState(profile=owner).destination(row(details=details))
        assertEquals("Devbox",target.label)
        assertEquals(AuthMethod.TailscaleSsh,target.profile().authMethod)
        assertEquals(2222,target.profile().port)
        assertEquals("devbox.example.ts.net",target.profile().host)
        assertNull(target.profile().privateKeyUri)
        assertEquals("work",target.profile().socket)
    }
    @Test fun disconnectedSavedTargetIsDistinctFromUnconfiguredTarget() {
        val profile=ConnectionProfile(host="remote",username="user",fingerprint="machine-b:user:other-socket")
        val state=DazeDevMobileUiState(profile=owner,hosts=listOf(HostSummary(profile,ConnectionPhase.Disconnected,1,profile.fingerprint)))
        val target=state.destination(row())
        assertEquals("Connect",target.action)
        assertEquals(profile,target.profile())
        assertEquals("Saved · reconnect",target.status)
    }
    @Test fun hostIdsNeverOverrideDifferentMachineIdentity() {
        val profile=owner.copy(id="desktop-b")
        val state=DazeDevMobileUiState(profile=owner,hosts=listOf(HostSummary(profile,ConnectionPhase.Connected,1,"different:user:other-socket")))
        assertNull(state.destination(row()).host)
        assertFalse(state.destination(row()).connected)
    }
    @Test fun connectedMatchingTargetWinsOverDisconnectedAlias() {
        val one=owner.copy(id="one",host="remote")
        val two=one.copy(id="two")
        val key="machine-b:user:other-socket"
        val state=DazeDevMobileUiState(profile=owner,hosts=listOf(HostSummary(one,ConnectionPhase.Disconnected,1,key),HostSummary(two,ConnectionPhase.Connected,1,key)))
        assertEquals(two,state.destination(row()).profile())
        assertEquals("Open",state.destination(row()).action)
    }
    @Test fun ownHostIsClearlyMarkedAndBlankIdentityNeverMatches() {
        val state=DazeDevMobileUiState(profile=owner,hosts=listOf(HostSummary(owner,ConnectionPhase.Connected,1,"owner:user:")))
        assertFalse(state.destination(row("owner:user:")).otherHost)
        assertTrue(state.destination(row("owner:user:")).connected)
        assertNull(state.destination(row("")).host)
    }
}

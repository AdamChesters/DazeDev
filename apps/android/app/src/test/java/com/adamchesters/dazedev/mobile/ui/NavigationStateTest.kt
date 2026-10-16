package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.WorkspaceFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStateTest {
    private val host = ConnectionProfile(id = "h1", name = "Dev", host = "dev.example", username = "me")
    private val hosts = mapOf("h1" to host)

    @Test fun aTerminalComesBackAfterProcessDeathWaitingForReconnect() {
        val before =
            DazeDevMobileUiState(profile = host, selectedFolder = "f1", selectedPaneId = "p1")
                .atHost("h1")
                .withTerminal("h1", "$3")
                .copy(selectedSessionName = "main", selectedSessionId = "$3", selectedStamp = "s3", selectedTargetHost = "h1", selectedTargetLabel = "Dev")
        val after = DazeDevMobileUiState().restoreNavigation(before.navigationJson(), hosts::get)
        assertEquals(before.backStack, after.backStack)
        assertEquals("h1", after.profile.id)
        assertEquals("f1", after.selectedFolder)
        assertEquals("p1", after.selectedPaneId)
        assertEquals("main", after.selectedSessionName)
        assertEquals("s3", after.selectedStamp)
        assertEquals("h1", after.selectedTargetHost)
        assertEquals("Android closed DazeDev in the background. Reconnect to continue.", after.terminalError)
    }

    @Test fun removedHostsAndGroupsAreDropped() {
        val group = WorkspaceFolder("g1", "Group", emptyList())
        val json = DazeDevMobileUiState().atLocalGroup("g1").withTerminal("gone", "$1").navigationJson()
        val kept = DazeDevMobileUiState(localGroups = listOf(group)).restoreNavigation(json, hosts::get)
        assertEquals(listOf(Screen.Hosts, Screen.LocalGroup("g1")), kept.backStack)
        assertNull(kept.selectedSessionId)
        assertEquals(Screen.Hosts, DazeDevMobileUiState().restoreNavigation(json, hosts::get).screen)
        val plain = DazeDevMobileUiState()
        assertEquals(plain, plain.restoreNavigation("not json", hosts::get))
    }
}

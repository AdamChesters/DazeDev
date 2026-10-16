package com.adamchesters.dazedev.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTest {
    @Test fun terminalsStackOnTheirWorkspaceAndLeaveIt() {
        val group = DazeDevMobileUiState().atLocalGroup("g1")
        assertEquals("g1", group.localGroupId)
        val open = group.withTerminal("h1", "$1").copy(selectedSessionName = "main", selectedSessionId = "$1")
        assertEquals(listOf(Screen.Hosts, Screen.LocalGroup("g1"), Screen.Terminal("h1", "$1")), open.backStack)
        // The group stays selected while its terminal is open.
        assertEquals("g1", open.localGroupId)
        val again = open.withTerminal("h2", "$2")
        assertEquals(Screen.Terminal("h2", "$2"), again.screen)
        assertEquals(3, again.backStack.size)
        val closed = again.withoutTerminal()
        assertEquals(Screen.LocalGroup("g1"), closed.screen)
        assertNull(closed.selectedSessionName)
        assertNull(closed.selectedSessionId)
    }

    @Test fun hostAndHostListResetTheStack() {
        val host = DazeDevMobileUiState().atLocalGroup("g1").atHost("h1")
        assertEquals(listOf(Screen.Hosts, Screen.Host("h1")), host.backStack)
        assertNull(host.localGroupId)
        assertEquals(Screen.Hosts, host.atHosts().screen)
        assertEquals(Screen.Hosts, DazeDevMobileUiState(backStack = emptyList()).screen)
    }
}

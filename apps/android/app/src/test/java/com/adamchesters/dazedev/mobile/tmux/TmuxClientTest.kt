package com.adamchesters.dazedev.mobile.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxClientTest {
    @Test
    fun parsesAndSortsSessionMetadata() {
        val output = listOf(
            "quiet|1|0|100|bash",
            "attached|2|1|90|claude",
            "recent|1|0|120|codex",
        ).joinToString("\n")

        val sessions = TmuxClient.parseSessions(output)

        assertEquals(listOf("attached", "recent", "quiet"), sessions.map(TmuxSession::name))
        assertEquals(2, sessions.first().windowCount)
        assertEquals("claude", sessions.first().currentCommand)
    }

    @Test
    fun ignoresMalformedRowsAndEmptyOutput() {
        assertTrue(TmuxClient.parseSessions("").isEmpty())
        assertTrue(TmuxClient.parseSessions("not a tmux row").isEmpty())
    }

    @Test
    fun targetsTheActivePaneOfAnExactSession() {
        assertEquals("=feature-work:", TmuxClient.exactPaneTarget("feature-work"))
    }

    @Test
    fun endsOnlyTheExactNamedSession() {
        assertEquals("=feature-work", TmuxClient.exactSessionTarget("feature-work"))
        assertEquals(
            "tmux kill-session -t '=feature-work'",
            TmuxClient.endSessionCommand("feature-work"),
        )
    }

    @Test
    fun compactsFullScreenBlankRowsAndTrailingSpaces() {
        val capture = "response  \r\n\r\n\r\n\r\nstatus bar  \r\n\r\n"

        assertEquals("response\n\nstatus bar", TmuxClient.normalizeCapture(capture))
    }

    @Test
    fun launchesInstalledCodexCliWithoutManagedRemoteControl() {
        val command = TmuxClient.agentStartupCommand("feature-work", AgentKind.Codex)

        assertEquals("exec codex --no-alt-screen", command)
        assertFalse(command.contains("remote-control"))
    }

    @Test
    fun launchesClaudeWithFlatTerminalRendering() {
        val command = TmuxClient.agentStartupCommand("feature-work", AgentKind.Claude)

        assertTrue(command.contains("--ax-screen-reader"))
        assertTrue(command.contains("--remote-control 'feature-work'"))
    }
}

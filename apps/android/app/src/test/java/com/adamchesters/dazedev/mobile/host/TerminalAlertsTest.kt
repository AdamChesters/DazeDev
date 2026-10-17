package com.adamchesters.dazedev.mobile.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalAlertsTest {
    // The same cases as the desktop's tests/terminal-alerts.test.ts.
    @Test fun acceptsExplicitNotificationsWhileExcludingProgressAndControlSequences() {
        assertEquals("Ready for your reply", TerminalAlerts.notification(9, "Ready for your reply"))
        assertEquals("Agent: Finished", TerminalAlerts.notification(777, "notify;Agent;Finished"))
        assertNull(TerminalAlerts.notification(9, "4;1;50"))
        assertNull(TerminalAlerts.notification(777, "dazedev-shell"))
        assertNull(TerminalAlerts.notification(777, "dazedev-ready:secret:123"))
        assertEquals(240, TerminalAlerts.notification(9, "a".repeat(1000))?.length)
        assertEquals("Done", TerminalAlerts.notification(9, "Do\u0007ne"))
    }

    @Test fun throttlesAlertsPerTerminal() {
        val throttle = AlertThrottle(3000)
        assertTrue(throttle.allow("a", 0))
        assertFalse(throttle.allow("a", 1000))
        assertTrue(throttle.allow("b", 1000))
        assertTrue(throttle.allow("a", 3000))
    }

    @Test fun reportsAFinishedCommandOnlyWhenUnwatchedOrLongRunning() {
        assertFalse(TerminalAlerts.commandFinished(0, 2000, watched = true))
        assertTrue(TerminalAlerts.commandFinished(0, 2000, watched = false))
        assertTrue(TerminalAlerts.commandFinished(0, 10_000, watched = true))
    }

    @Test fun scannerReadsSequencesSplitAcrossChunksWithEitherTerminator() {
        val seen = mutableListOf<Pair<Int, String>>()
        val scanner = OscScanner { code, data -> seen.add(code to data) }
        val bytes = "text\u001b]9;Build 界 done\u0007more\u001b]777;notify;Agent;Ready\u001b\\\u001b]0;title\u0007".toByteArray()
        for (i in bytes.indices) scanner.feed(bytes, i, 1)
        assertEquals(listOf(9 to "Build 界 done", 777 to "notify;Agent;Ready", 0 to "title"), seen)
    }

    @Test fun scannerSkipsOverlongAndCancelledSequences() {
        val seen = mutableListOf<Int>()
        val scanner = OscScanner(maxLength = 16) { code, _ -> seen.add(code) }
        scanner.feed("\u001b]9;${"x".repeat(100)}\u0007\u001b]9;cut\u0018\u0007\u001b]9;ok\u0007".toByteArray())
        assertEquals(listOf(9), seen)
        // Non-numeric codes and plain text never alert.
        scanner.feed("\u001b]abc;x\u0007 9;plain\u0007".toByteArray())
        assertEquals(listOf(9), seen)
    }

    @Test fun watcherReportsNotificationsAndUnwatchedCommands() {
        var now = 0L
        var watched = true
        val alerts = mutableListOf<String>()
        val watcher = TerminalAlertWatcher({ now }, { watched }) { alerts.add(it) }
        watcher.feed("\u001b]133;C\u0007".toByteArray())
        now = 2_000
        watcher.feed("\u001b]133;D;0\u0007".toByteArray())
        assertTrue(alerts.isEmpty())
        watcher.feed("\u001b]133;C\u0007".toByteArray())
        watched = false
        now = 3_000
        watcher.feed("\u001b]133;D;0\u0007\u001b]133;D\u0007".toByteArray())
        assertEquals(listOf("Command finished"), alerts)
        watcher.feed("\u001b]9;Needs input\u001b\\".toByteArray())
        assertEquals(listOf("Command finished", "Needs input"), alerts)
    }
}

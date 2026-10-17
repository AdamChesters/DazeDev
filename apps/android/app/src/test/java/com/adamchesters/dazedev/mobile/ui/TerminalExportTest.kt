package com.adamchesters.dazedev.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class TerminalExportTest {
    @Test fun fileNamesAreSafeAndDated() {
        val time = LocalDateTime.of(2026, 10, 17, 9, 30)
        assertEquals("build-server-20261017-0930.txt", TerminalExport.fileName("build server", time))
        assertEquals("a-b-20261017-0930.txt", TerminalExport.fileName("../a/b", time))
        assertEquals("terminal-20261017-0930.txt", TerminalExport.fileName("界", time))
        assertEquals(60 + "-20261017-0930.txt".length, TerminalExport.fileName("x".repeat(200), time).length)
    }
}

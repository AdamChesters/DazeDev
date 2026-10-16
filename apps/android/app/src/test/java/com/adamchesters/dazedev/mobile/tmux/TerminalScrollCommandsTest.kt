package com.adamchesters.dazedev.mobile.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollCommandsTest {
    @Test fun quotesSocketAndStampAsLiteralArguments() {
        val command=TerminalScrollCommands.cancel("socket'; touch /tmp/no", "\$7", "stamp'; echo no")
        assertTrue(command.startsWith("python3 -I -c "))
        assertTrue(command.endsWith("${ShellQuoter.quote("socket'; touch /tmp/no")} '\$7' ${ShellQuoter.quote("stamp'; echo no")}"))
    }
    @Test fun neverEntersCopyMode() {
        assertFalse(TerminalScrollCommands.script.contains("'copy-mode','-e'"))
        assertFalse(TerminalScrollCommands.script.contains("scroll-up"))
        assertTrue(TerminalScrollCommands.script.contains("'cancel'"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonSessionTargets() {
        TerminalScrollCommands.cancel("", "other-session", "stamp")
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingSessionStamp() {
        TerminalScrollCommands.cancel("", "\$1", "")
    }
}

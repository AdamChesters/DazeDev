package com.adamchesters.dazedev.mobile.tmux

import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScrollCommandsTest {
    @Test fun quotesSocketAndStampAsLiteralArguments() {
        val command=TerminalScrollCommands.command("socket'; touch /tmp/no", "\$7", "stamp'; echo no", -12)
        assertTrue(command.endsWith("${ShellQuoter.quote("socket'; touch /tmp/no")} '\$7' ${ShellQuoter.quote("stamp'; echo no")} -12 0"))
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsNonSessionTargets() {
        TerminalScrollCommands.command("", "other-session", "stamp", -1)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsUnboundedScrollRequests() {
        TerminalScrollCommands.command("", "\$1", "stamp", Int.MIN_VALUE)
    }
    @Test(expected=IllegalArgumentException::class) fun rejectsMissingSessionStamp() {
        TerminalScrollCommands.command("", "\$1", "", -1)
    }
}

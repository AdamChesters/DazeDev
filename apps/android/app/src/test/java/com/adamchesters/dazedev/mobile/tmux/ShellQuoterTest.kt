package com.adamchesters.dazedev.mobile.tmux

import org.junit.Assert.assertEquals
import org.junit.Test

class ShellQuoterTest {
    @Test
    fun quotesSimpleText() {
        assertEquals("'hello world'", ShellQuoter.quote("hello world"))
    }

    @Test
    fun escapesSingleQuotesWithoutInterpolation() {
        assertEquals("'it'\"'\"'s \$(safe)'", ShellQuoter.quote("it's \$(safe)"))
    }

    @Test
    fun quotesEmptyText() {
        assertEquals("''", ShellQuoter.quote(""))
    }
    @Test(expected = IllegalArgumentException::class)
    fun rejectsNullBytes() {
        ShellQuoter.quote("command\u0000suffix")
    }

}

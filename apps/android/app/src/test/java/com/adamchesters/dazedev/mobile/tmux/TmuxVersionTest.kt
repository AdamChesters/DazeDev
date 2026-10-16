package com.adamchesters.dazedev.mobile.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxVersionTest {
    @Test fun ignoreSizeNeedsTmux32OrNewer() {
        listOf("tmux 3.2", "tmux 3.2a", "tmux 3.4\n", "tmux next-3.6", "tmux 4.0").forEach { assertTrue(it, TmuxVersion.supportsIgnoreSize(it)) }
        listOf("tmux 3.1c", "tmux 2.9a", "tmux master", "", "command not found").forEach { assertFalse(it, TmuxVersion.supportsIgnoreSize(it)) }
    }
}

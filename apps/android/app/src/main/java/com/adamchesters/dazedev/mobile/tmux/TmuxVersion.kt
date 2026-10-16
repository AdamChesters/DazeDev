package com.adamchesters.dazedev.mobile.tmux

object TmuxVersion {
    /** True when `tmux -V` output names version 3.2 or newer, which has attach-session -f ignore-size. */
    fun supportsIgnoreSize(output: String): Boolean {
        val match = Regex("(\\d+)\\.(\\d+)").find(output) ?: return false
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        return major > 3 || (major == 3 && minor >= 2)
    }
}

package com.adamchesters.dazedev.mobile.tmux

object ShellQuoter {
    fun quote(value: String): String {
        require('\u0000' !in value) { "Shell arguments cannot contain a null character." }
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}

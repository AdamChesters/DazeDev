package com.adamchesters.dazedev.mobile.tmux

data class TmuxSession(
    val name: String,
    val windowCount: Int,
    val attachedClients: Int,
    val lastActivityEpochSeconds: Long,
    val currentCommand: String,
    val id: String = "",
    val stamp: String = "",
)

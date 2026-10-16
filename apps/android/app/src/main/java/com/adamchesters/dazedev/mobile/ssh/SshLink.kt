package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.jcraft.jsch.ChannelExec

/** One SSH connection, as the host client and terminals use it. Tests replace it with a fake. */
interface SshLink {
    val isConnected: Boolean

    suspend fun connect(
        profile: ConnectionProfile,
        secret: CharArray,
        privateKey: ByteArray?,
    )

    suspend fun execute(
        command: String,
        timeoutMs: Long = 15_000L,
    ): ExecResult

    suspend fun openStream(
        command: String,
        pty: Boolean = false,
        cols: Int = 80,
        rows: Int = 24,
    ): ChannelExec

    suspend fun disconnect()

    fun disconnectNow()
}

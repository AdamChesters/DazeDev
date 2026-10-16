package com.adamchesters.dazedev.mobile.ssh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One attached terminal, as the terminal view uses it. */
interface TerminalLink {
    val output: Flow<ByteArray>
    val state: StateFlow<Boolean>
    val connected: Boolean

    fun send(bytes: ByteArray): Boolean

    fun resize(
        cols: Int,
        rows: Int,
    )

    suspend fun cancelCopyMode()

    fun close()
}

data class AttachRequest(
    val socket: String,
    val id: String,
    val stamp: String,
    val cols: Int,
    val rows: Int,
    val browser: JSONObject?,
    /** Attach with tmux's ignore-size flag, so this phone does not resize other devices. */
    val ignoreSize: Boolean = false,
)

/** Opens terminals; a failed or cancelled attach leaves nothing open. */
fun interface TerminalOpener {
    suspend fun open(
        ssh: SshLink,
        scope: CoroutineScope,
        request: AttachRequest,
    ): TerminalLink
}

object SshTerminalOpener : TerminalOpener {
    override suspend fun open(
        ssh: SshLink,
        scope: CoroutineScope,
        request: AttachRequest,
    ): TerminalLink {
        val stream = TerminalStream(ssh, scope)
        try {
            stream.attach(request.socket, request.id, request.stamp, request.cols, request.rows, request.browser, request.ignoreSize)
        } catch (e: Throwable) {
            stream.close()
            throw e
        }
        return stream
    }
}

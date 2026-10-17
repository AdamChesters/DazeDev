package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshConnection
import com.adamchesters.dazedev.mobile.ssh.SshLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONObject
import java.io.File

sealed interface HostLinkEvent {
    data class Lost(
        val reason: String,
    ) : HostLinkEvent
}

/** The DazeDev helper running on one host. Tests replace it with a fake. */
interface HostLink {
    val isOpen: Boolean
    val events: SharedFlow<HostLinkEvent>

    suspend fun start(
        source: ByteArray,
        socket: String,
        browserSource: ByteArray = ByteArray(0),
        host: String = "this host",
    ): JSONObject

    suspend fun request(
        method: String,
        args: JSONObject = JSONObject(),
        timeoutMs: Long = 60_000,
    ): Any

    /** Asks the helper to answer at once; throws when it does not answer within [timeoutMs]. */
    suspend fun probe(timeoutMs: Long) {
        request("ping", JSONObject(), timeoutMs)
    }

    fun close()
}

/** Creates the SSH and helper links for each saved host. */
interface HostLinkFactory {
    fun ssh(
        hostId: String,
        prompts: HostKeyPromptCoordinator,
        onBanner: (String) -> Unit,
    ): SshLink

    fun host(
        ssh: SshLink,
        scope: CoroutineScope,
        onBrowserOpen: suspend (String) -> String?,
    ): HostLink
}

class SshHostLinkFactory(
    private val knownHosts: (String) -> File,
) : HostLinkFactory {
    override fun ssh(
        hostId: String,
        prompts: HostKeyPromptCoordinator,
        onBanner: (String) -> Unit,
    ): SshLink = SshConnection(knownHosts(hostId), prompts, onBanner)

    override fun host(
        ssh: SshLink,
        scope: CoroutineScope,
        onBrowserOpen: suspend (String) -> String?,
    ): HostLink = HostClient(ssh, scope, onBrowserOpen)
}

class HelperSources(
    val host: ByteArray,
    val browser: ByteArray,
)

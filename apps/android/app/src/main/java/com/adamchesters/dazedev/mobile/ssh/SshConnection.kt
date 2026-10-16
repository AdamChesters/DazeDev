package com.adamchesters.dazedev.mobile.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
) {
    fun requireSuccess(action: String): ExecResult {
        if (exitCode != 0) {
            val detail = stderr.trim().ifBlank { stdout.trim() }.ifBlank { "exit code $exitCode" }
            throw SshCommandException("$action failed: $detail")
        }
        return this
    }
}

class SshCommandException(message: String) : Exception(message)

class SshConnection(
    private val knownHostsFile: File,
    private val hostKeyPrompts: HostKeyPromptCoordinator,
    private val onBanner: (String) -> Unit = {},
) {
    private val commandMutex = Mutex()

    @Volatile
    private var session: Session? = null

    @Volatile
    private var jsch: JSch? = null

    val isConnected: Boolean
        get() = session?.isConnected == true

    suspend fun connect(
        profile: ConnectionProfile,
        secret: String,
        privateKey: ByteArray?,
    ) = withContext(Dispatchers.IO) {
        require(profile.validationError(secret) == null) { "Invalid SSH connection profile." }
        disconnectNow()
        prepareKnownHostsFile()

        val guard: GuardedHostKeys
        val nextJsch = JSch().apply {
            setKnownHosts(knownHostsFile.absolutePath)
            guard = GuardedHostKeys(hostKeyRepository)
            hostKeyRepository = guard
            if (profile.authMethod == AuthMethod.PrivateKey) {
                val key = requireNotNull(privateKey) { "Choose a private key file." }
                val passphrase = secret.takeIf(String::isNotEmpty)?.toByteArray(StandardCharsets.UTF_8)
                try {
                    addIdentity("dazedev-mobile", key, null, passphrase)
                } finally {
                    passphrase?.fill(0)
                }
            }
        }

        val passwordBytes = if (profile.authMethod == AuthMethod.Password) {
            secret.toByteArray(StandardCharsets.UTF_8)
        } else {
            null
        }
        val nextSession = nextJsch.getSession(profile.username, profile.host, profile.port).apply {
            passwordBytes?.let(::setPassword)
            userInfo = PromptingUserInfo(
                password = if (profile.authMethod == AuthMethod.Password) secret else "",
                hostKeyPrompts = hostKeyPrompts,
                guard = guard,
                onBanner = onBanner,
            )
            setConfig(Properties().apply {
                put("StrictHostKeyChecking", "ask")
                put("HashKnownHosts", "yes")
                put(
                    "PreferredAuthentications",
                    when (profile.authMethod) {
                        // JSch always attempts SSH "none" before these fallback methods.
                        AuthMethod.TailscaleSsh -> "none"
                        AuthMethod.PrivateKey -> "publickey"
                        AuthMethod.Password -> "password"
                    },
                )
            })
            serverAliveInterval = SERVER_ALIVE_INTERVAL_MS
            serverAliveCountMax = SERVER_ALIVE_COUNT_MAX
        }

        try {
            nextSession.connect(CONNECT_TIMEOUT_MS)
            currentCoroutineContext().ensureActive()
            nextSession.userInfo = null
            nextSession.setPassword(ByteArray(0))
            nextJsch.removeAllIdentity()
            jsch = nextJsch
            session = nextSession
        } catch (error: Exception) {
            nextSession.disconnect()
            nextJsch.removeAllIdentity()
            guard.changed?.let { throw HostKeyChangedException(it) }
            throw error
        } finally {
            passwordBytes?.fill(0)
        }
    }

    suspend fun execute(command: String, timeoutMs: Long = COMMAND_TIMEOUT_MS): ExecResult =
        withContext(Dispatchers.IO) {
            commandMutex.withLock {
                val activeSession = session?.takeIf(Session::isConnected)
                    ?: throw JSchException("SSH is not connected.")
                val channel = activeSession.openChannel("exec") as ChannelExec
                val stdout = BoundedOutputStream()
                val stderr = BoundedOutputStream()

                try {
                    channel.setInputStream(null)
                    channel.setOutputStream(stdout)
                    channel.setErrStream(stderr)
                    channel.setCommand(command)
                    channel.connect(CONNECT_TIMEOUT_MS)

                    withTimeout(timeoutMs) {
                        while (!channel.isClosed) {
                            delay(20)
                        }
                    }

                    if (stdout.exceeded || stderr.exceeded) {
                        throw SshCommandException("SSH output exceeded the 1 MiB limit.")
                    }
                    ExecResult(
                        stdout = stdout.text(),
                        stderr = stderr.text(),
                        exitCode = channel.exitStatus,
                    )
                } catch (_: TimeoutCancellationException) {
                    throw SshCommandException("SSH command timed out.")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } finally {
                    channel.disconnect()
                    stdout.close()
                    stderr.close()
                }
            }
        }

    suspend fun openStream(command: String, pty: Boolean = false, cols: Int = 80, rows: Int = 24): ChannelExec = withContext(Dispatchers.IO) {
        val active = session?.takeIf(Session::isConnected) ?: throw JSchException("SSH is not connected.")
        (active.openChannel("exec") as ChannelExec).apply {
            setCommand(command)
            setPty(pty)
            if (pty) setPtyType("xterm-256color", cols, rows, 0, 0)
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        disconnectNow()
    }

    fun disconnectNow() {
        hostKeyPrompts.cancel()
        session?.disconnect()
        session = null
        jsch?.removeAllIdentity()
        jsch = null
    }

    private fun prepareKnownHostsFile() {
        knownHostsFile.parentFile?.mkdirs()
        if (!knownHostsFile.exists() && !knownHostsFile.createNewFile()) {
            throw IllegalStateException("Could not create the SSH known hosts file.")
        }
    }

    private class PromptingUserInfo(
        private val password: String,
        private val hostKeyPrompts: HostKeyPromptCoordinator,
        private val guard: GuardedHostKeys,
        private val onBanner: (String) -> Unit,
    ) : UserInfo {
        override fun getPassphrase(): String? = null

        override fun getPassword(): String = password

        override fun promptPassword(message: String?): Boolean = password.isNotEmpty()

        override fun promptPassphrase(message: String?): Boolean = false

        // A changed key is refused here; only a first-seen key reaches the trust prompt.
        override fun promptYesNo(message: String?): Boolean {
            if (guard.changed != null) return false
            val notice = guard.unknown ?: return false
            return hostKeyPrompts.request(
                "First connection to ${notice.host}.\n\n${notice.type} key fingerprint:\n${notice.fingerprint}",
            )
        }

        // Servers such as Tailscale SSH in check mode send an approval link here.
        override fun showMessage(message: String?) {
            message?.trim()?.takeIf(String::isNotEmpty)?.let { onBanner(it.take(4000)) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val COMMAND_TIMEOUT_MS = 15_000L
        const val SERVER_ALIVE_INTERVAL_MS = 15_000
        const val SERVER_ALIVE_COUNT_MAX = 3
    }
}

package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.Fixtures
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Opt-in tests against a disposable SSH fixture; never use a real account or key. */
class SshConnectionIntegrationTest {
    private fun port(): Int = Fixtures.env("DAZEDEV_TEST_SSH_PORT", "Requires a disposable SSH fixture").toInt()

    private fun connectionTest(
        method: AuthMethod,
        username: String,
        secret: String,
        host: String = "127.0.0.1",
        acceptHostKey: Boolean = true,
        overflow: Boolean = false,
        portOverride: Int? = null,
        changedKey: Boolean = false,
        keyVariable: String = "DAZEDEV_TEST_SSH_KEY",
        hostKeyType: String? = null,
    ) = runBlocking {
        val port = portOverride ?: port()
        val knownHosts = File.createTempFile("dazedev-mobile-test-", "-hosts")
        if (changedKey) {
            val oldKey =
                com.jcraft.jsch.KeyPair
                    .genKeyPair(com.jcraft.jsch.JSch(), com.jcraft.jsch.KeyPair.RSA, 2048)
            try {
                val encoded =
                    java.util.Base64
                        .getEncoder()
                        .encodeToString(oldKey.publicKeyBlob)
                knownHosts.writeText("[127.0.0.1]:$port ssh-rsa $encoded\n")
            } finally {
                oldKey.dispose()
            }
        }
        val prompts = HostKeyPromptCoordinator()
        val ssh = SshConnection(knownHosts, prompts)
        val answer =
            launch {
                prompts.challenge.filterNotNull().collect {
                    if (changedKey) throw AssertionError("A changed host key must never be offered for trust")
                    assertTrue(it.message.contains("SHA256:"))
                    prompts.answer(it.id, acceptHostKey)
                }
            }
        val key =
            if (method == AuthMethod.PrivateKey) {
                File(Fixtures.env(keyVariable, "Requires a disposable SSH fixture key")).readBytes()
            } else {
                null
            }
        try {
            val profile =
                ConnectionProfile(
                    host,
                    port,
                    username,
                    method,
                    privateKeyUri = if (key != null) "content://fixture/key" else null,
                )
            ssh.connect(profile, secret, key)
            assertTrue(ssh.isConnected)
            assertEquals("fixture-ok\n", ssh.execute("probe").requireSuccess("Probe").stdout)
            assertTrue(knownHosts.readText().isNotBlank())
            hostKeyType?.let { assertEquals(listOf(it), KnownHostKeys.list(knownHosts).map(SavedHostKey::type)) }
            assertFalse(knownHosts.readText().contains(host))
            if (overflow) ssh.execute("overflow")
            // A reconnect with the saved host key must work without another trust prompt.
            answer.cancelAndJoin()
            ssh.disconnect()
            withTimeout(5_000) { ssh.connect(profile, secret, key) }
            assertTrue(ssh.isConnected)
        } finally {
            key?.fill(0)
            ssh.disconnect()
            answer.cancelAndJoin()
            knownHosts.delete()
        }
    }

    @Test fun passwordOverDirectTcp() = connectionTest(AuthMethod.Password, "password-user", "fixture-password")

    @Test fun encryptedPrivateKeyOverDirectTcp() = connectionTest(AuthMethod.PrivateKey, "key-user", "fixture-passphrase")

    // Uses the Bouncy Castle classes JSch needs on Android for Ed25519 user and host keys.
    @Test fun ed25519KeyAndHostKey() =
        connectionTest(
            AuthMethod.PrivateKey,
            "ed-user",
            "fixture-passphrase",
            keyVariable = "DAZEDEV_TEST_SSH_ED25519_KEY",
            hostKeyType = "ssh-ed25519",
        )

    @Test fun credentialFreeSsh() = connectionTest(AuthMethod.TailscaleSsh, "none-user", "")

    @Test fun hostnameOverDirectTcp() = connectionTest(AuthMethod.Password, "password-user", "fixture-password", "localhost")

    @Test(expected = com.jcraft.jsch.JSchException::class)
    fun rejectsUntrustedHost() = connectionTest(AuthMethod.Password, "password-user", "fixture-password", acceptHostKey = false)

    @Test(expected = com.jcraft.jsch.JSchException::class)
    fun rejectedKeyDoesNotFallBackToPassword() = connectionTest(AuthMethod.PrivateKey, "reject-key-user", "fixture-passphrase")

    @Test(expected = SshCommandException::class)
    fun boundsRemoteOutput() = connectionTest(AuthMethod.Password, "password-user", "fixture-password", overflow = true)

    @Test fun passwordOverIpv6() {
        val ipv6Port = System.getenv("DAZEDEV_TEST_SSH_PORT_V6")
        assumeTrue("Requires an IPv6 loopback fixture", !ipv6Port.isNullOrBlank())
        connectionTest(
            AuthMethod.Password,
            "password-user",
            "fixture-password",
            "::1",
            portOverride = requireNotNull(ipv6Port).toInt(),
        )
    }

    // A changed key fails without any trust prompt, even when the user would tap Trust.
    @Test(expected = HostKeyChangedException::class)
    fun rejectsChangedHostKey() =
        connectionTest(
            AuthMethod.Password,
            "password-user",
            "fixture-password",
            acceptHostKey = true,
            changedKey = true,
        )
}

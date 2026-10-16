package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.Fixtures
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshConnection
import com.adamchesters.dazedev.mobile.ssh.TerminalStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserIntegrationTest {
    @Test fun remoteOpenReachesMobileCallbackAndAcknowledgesWithoutBlockingReader() =
        runBlocking {
            val folder = File(Fixtures.env("DAZEDEV_TEST_REAL_FIXTURE", "Requires disposable real host fixture"))
            val port = JSONObject(File(folder, "fixture-ports.json").readText()).getInt("127.0.0.1")
            val socket = File(folder, "fixture-socket.txt").readText()
            val hostSource = File(File(folder, "fixture-host-source.txt").readText())
            // The fixture copies the helpers with a private bridge folder; never run the shared copies.
            assertTrue("Browser helper must be the fixture's isolated copy", hostSource.canonicalPath.startsWith(folder.canonicalPath + File.separator))
            assertFalse(File(hostSource.parentFile, "browser.py").readText().contains("'/tmp'"))
            val known = File.createTempFile("dazedev-browser-test", "hosts")
            val prompts = HostKeyPromptCoordinator()
            val ssh = SshConnection(known, prompts)
            val trust = launch { prompts.challenge.filterNotNull().collect { prompts.answer(it.id, true) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val opened = CompletableDeferred<String>()
            val client =
                HostClient(ssh, scope, onBrowserOpen = { url ->
                    opened.complete(url)
                    null
                })
            var terminal: TerminalStream? = null
            var target: JSONObject? = null
            Dispatchers.setMain(UnconfinedTestDispatcher())
            try {
                val key = File(folder, "fixture-key.pem").readBytes()
                try {
                    ssh.connect(ConnectionProfile("127.0.0.1", port, "key-user", AuthMethod.PrivateKey, "content://fixture/key"), "fixture-passphrase", key)
                } finally {
                    key.fill(0)
                }
                client.start(hostSource.readBytes(), socket, File(hostSource.parentFile, "browser.py").readBytes())
                val session =
                    client.request(
                        "create",
                        JSONObject()
                            .put("name", "browser-${UUID.randomUUID().toString().take(8)}")
                            .put("agent", "shell")
                            .put("path", File(folder, "home").absolutePath)
                            .put("operation", UUID.randomUUID().toString()),
                    ) as JSONObject
                target = JSONObject().put("id", session.getString("id")).put("stamp", session.getString("stamp"))
                val browser = client.request("browser_prepare") as JSONObject
                val active = TerminalStream(ssh, scope)
                terminal = active
                active.attach(socket, session.getString("id"), session.getString("stamp"), 80, 24, browser)
                val completed = CompletableDeferred<Unit>()
                val output = StringBuilder()
                val collector =
                    scope.launch {
                        active.output.collect { bytes ->
                            output.append(bytes.toString(Charsets.UTF_8))
                            if (output.contains("BROWSER_ACK_OK")) completed.complete(Unit)
                        }
                    }
                assertTrue(active.send("\"\$BROWSER\" https://example.com/login && printf 'BROWSER_%s\\n' 'ACK_OK'\r".toByteArray()))
                val url = withTimeoutOrNull(10_000) { opened.await() } ?: throw AssertionError("Browser request never reached the phone callback: $output")
                assertEquals("https://example.com/login", url)
                try {
                    withTimeout(10_000) { completed.await() }
                } catch (e: Exception) {
                    throw AssertionError("Browser shim did not receive its successful acknowledgement: $output", e)
                }
                assertEquals(1, (client.request("capabilities") as JSONObject).getInt("protocol"))
                collector.cancelAndJoin()
            } finally {
                terminal?.close()
                target?.let { runCatching { client.request("destroy", it) } }
                client.close()
                ssh.disconnect()
                trust.cancelAndJoin()
                scope.cancel()
                known.delete()
                Dispatchers.resetMain()
            }
        }
}

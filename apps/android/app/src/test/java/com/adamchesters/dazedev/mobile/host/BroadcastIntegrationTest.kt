package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.Fixtures
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.UUID

/** Broadcast against the disposable real host fixture: its own tmux server, never a live session. */
class BroadcastIntegrationTest {
    @Test fun pastesOnceWithReturnAndRefusesARestartedSession(): Unit =
        runBlocking {
            val folder = File(Fixtures.env("DAZEDEV_TEST_REAL_FIXTURE", "Requires disposable real host fixture"))
            val port = JSONObject(File(folder, "fixture-ports.json").readText()).getInt("127.0.0.1")
            val socket = File(folder, "fixture-socket.txt").readText()
            val source = File(File(folder, "fixture-host-source.txt").readText()).readBytes()
            val known = File.createTempFile("dazedev-broadcast-test", "hosts")
            val prompts = HostKeyPromptCoordinator()
            val ssh = SshConnection(known, prompts)
            val trust = launch { prompts.challenge.filterNotNull().collect { prompts.answer(it.id, true) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HostClient(ssh, scope)
            try {
                val key = File(folder, "fixture-key.pem").readBytes()
                try {
                    ssh.connect(ConnectionProfile("127.0.0.1", port, "key-user", AuthMethod.PrivateKey, "content://fixture/key"), "fixture-passphrase".toCharArray(), key)
                } finally {
                    key.fill(0)
                }
                client.start(source, socket)
                val home = File(folder, "home")
                val args =
                    JSONObject()
                        .put("name", "bc-${UUID.randomUUID().toString().take(8)}")
                        .put("agent", "shell")
                        .put("path", home.absolutePath)
                        .put("operation", UUID.randomUUID().toString())
                val session = client.request("create", args) as JSONObject
                val target = JSONObject().put("id", session.getString("id")).put("stamp", session.getString("stamp"))
                try {
                    // Wait for the shell prompt, so the pasted line reaches a shell that reads it.
                    withTimeout(10_000) {
                        while (ssh.execute("tmux -L '$socket' capture-pane -p -t '${session.getString("id")}'").stdout.isBlank()) delay(100)
                    }
                    val marker = File(home, "broadcast-${UUID.randomUUID().toString().take(8)}.txt")
                    val recipient = BroadcastRecipient("fixture", "h", session.getString("id"), session.getString("stamp"), "i")
                    Broadcast.deliver(ssh, socket, recipient, "printf '%s\\n' \"broadcast 界\" >> ${marker.name}")
                    withTimeout(10_000) { while (!marker.exists() || marker.readText().isEmpty()) delay(100) }
                    delay(500)
                    assertEquals("broadcast 界\n", marker.readText())
                    try {
                        Broadcast.deliver(ssh, socket, recipient.copy(stamp = "1:2:3"), "printf stale >> ${marker.name}")
                        fail("Expected a restarted session to be refused")
                    } catch (e: IllegalStateException) {
                        assertTrue(e.message.orEmpty().contains("nothing was sent"))
                    }
                    delay(500)
                    assertEquals("broadcast 界\n", marker.readText())
                    marker.delete()
                } finally {
                    client.request("destroy", target)
                }
            } finally {
                client.close()
                ssh.disconnect()
                trust.cancelAndJoin()
                scope.cancel()
                known.delete()
            }
        }
}

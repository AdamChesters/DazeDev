package com.adamchesters.dazedev.mobile.voice

import com.adamchesters.dazedev.mobile.Fixtures
import com.adamchesters.dazedev.mobile.data.AuthMethod
import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.host.HostClient
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The phone's dictionary file passes host.py's validation and merges with what is saved there. */
class VoiceDictionaryIntegrationTest {
    @Test fun syncWritesAFileTheHelperAcceptsAndMergesIt(): Unit =
        runBlocking {
            val folder = File(Fixtures.env("DAZEDEV_TEST_REAL_FIXTURE", "Requires disposable real host fixture"))
            val port = JSONObject(File(folder, "fixture-ports.json").readText()).getInt("127.0.0.1")
            val socket = File(folder, "fixture-socket.txt").readText()
            val source = File(File(folder, "fixture-host-source.txt").readText()).readBytes()
            val known = File.createTempFile("dazedev-dictionary-test", "hosts")
            val prompts = HostKeyPromptCoordinator()
            val ssh = SshConnection(known, prompts)
            val trust = launch { prompts.challenge.filterNotNull().collect { prompts.answer(it.id, true) } }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val client = HostClient(ssh, scope)
            val local = Files.createTempDirectory("dazedev-dictionary-phone").toFile()
            try {
                val key = File(folder, "fixture-key.pem").readBytes()
                try {
                    ssh.connect(ConnectionProfile("127.0.0.1", port, "key-user", AuthMethod.PrivateKey, "content://fixture/key"), "fixture-passphrase".toCharArray(), key)
                } finally {
                    key.fill(0)
                }
                client.start(source, socket)
                // Another device saved an entry first.
                val before = client.request("dictionary_read") as JSONObject
                val desktop = VoiceDictionary.edit(Dictionary(), "desktop-device", "desktop-entry", "DazeDev", listOf("days dev"))
                val existing = before.opt("dictionary").takeIf { it is JSONObject }?.let { VoiceDictionary.parse(it) } ?: Dictionary()
                val written = client.request("dictionary_write", JSONObject().put("dictionary", VoiceDictionary.toJson(VoiceDictionary.merge(existing, desktop))).put("revision", before.optString("revision"))) as JSONObject
                assertTrue(written.getBoolean("saved"))
                val store = VoiceDictionaryStore(File(local, "dictionary.json")) { _, method, args -> client.request(method, args) }
                store.load()
                store.saveEntry("phone-entry", "tmux", listOf("tee mux"))
                store.setHost("fixture")
                store.sync(adopt = true)
                val status = store.view.value.status
                assertTrue(status, status.startsWith("Synced"))
                val saved = VoiceDictionary.parse((client.request("dictionary_read") as JSONObject).getJSONObject("dictionary"))
                assertTrue(VoiceDictionary.same(saved, store.dictionary))
                assertEquals("DazeDev and tmux", VoiceDictionary.apply("days dev and tee mux", saved))
            } finally {
                client.close()
                ssh.disconnect()
                trust.cancelAndJoin()
                scope.cancel()
                known.delete()
                local.deleteRecursively()
            }
        }
}

package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.*
import com.adamchesters.dazedev.mobile.ssh.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class HostIntegrationTest {
    @Test fun sharedHelperConflictIdempotencyStaleTargetAndInteractivePty() = runBlocking {
        val root=System.getenv("DAZEDEV_TEST_REAL_FIXTURE")
        assumeTrue("Requires disposable real host fixture",root!=null)
        val folder=File(requireNotNull(root))
        val port=JSONObject(File(folder,"fixture-ports.json").readText()).getInt("127.0.0.1")
        val socket=File(folder,"fixture-socket.txt").readText()
        val source=File(File(folder,"fixture-host-source.txt").readText()).readBytes()
        val known=File.createTempFile("dazedev-host-test","hosts")
        val prompts=HostKeyPromptCoordinator()
        val ssh=SshConnection(known,prompts)
        val trust=launch {prompts.challenge.filterNotNull().collect {prompts.answer(it.id,true)}}
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val client=HostClient(ssh,scope);val other=HostClient(ssh,scope)
        var stream:TerminalStream?=null
        try {
            val key=File(folder,"fixture-key.pem").readBytes()
            try {ssh.connect(ConnectionProfile("127.0.0.1",port,"key-user",AuthMethod.PrivateKey,"content://fixture/key"),"fixture-passphrase",key)}finally{key.fill(0)}
            val ready=client.start(source,socket);assertTrue(ready.getString("machine").isNotBlank())
            other.start(source,socket)
            val capabilities=client.request("capabilities") as JSONObject
            assertEquals(1,capabilities.getInt("protocol"))
            assertEquals("ssh-tmux",capabilities.getString("transport"))
            val original=client.request("profile_load") as JSONObject
            val competing=other.request("profile_load") as JSONObject
            val revision=original.getInt("revision")
            original.put("futureMobile",JSONObject().put("keep",true))
            val saved=client.request("profile_save",JSONObject().put("revision",revision).put("profile",original)) as JSONObject
            assertEquals(revision+1,saved.getInt("revision"))
            try {other.request("profile_save",JSONObject().put("revision",revision).put("profile",competing));fail("Expected conflict")}catch(e:Exception){assertTrue(e.message.orEmpty().contains("CONFLICT"))}
            assertTrue((other.request("profile_load") as JSONObject).getJSONObject("futureMobile").getBoolean("keep"))
            val args=JSONObject().put("name","test-${UUID.randomUUID().toString().take(8)}").put("agent","shell").put("path",File(folder,"home").absolutePath).put("operation",UUID.randomUUID().toString())
            val session=client.request("create",args) as JSONObject
            assertEquals(session.getString("id"),(other.request("create",args) as JSONObject).getString("id"))
            val target=JSONObject().put("id",session.getString("id")).put("stamp",session.getString("stamp"))
            assertTrue((client.request("session_context",target) as String).startsWith(session.getString("id")))
            try{client.request("session_context",JSONObject(target.toString()).put("stamp","stale"));fail("Expected stale target rejection")}catch(e:Exception){assertTrue(e.message.orEmpty().contains("Session changed"))}
            stream=TerminalStream(ssh,scope)
            stream.attach(socket,session.getString("id"),session.getString("stamp"),80,24)
            val attached=stream
            val output=StringBuilder()
            val received=async {withTimeout(15_000){attached.output.first { bytes->output.append(bytes.toString(Charsets.UTF_8));output.contains("\r\nPTY_OK_界\r\n")}}}
            stream.resize(72,20)
            assertTrue(stream.send("stty -echo; printf '\\nPTY_OK_界\\n'\r".toByteArray()))
            try {received.await()}catch(e:Exception){throw AssertionError("PTY never ran the Unicode command; output: $output",e)}
            val resized=StringBuilder()
            val resizeConfirmed=async {withTimeout(10_000){attached.output.first {bytes->resized.append(bytes.toString(Charsets.UTF_8));resized.contains("SIZE_OK_19 72\r\n")}}}
            assertTrue(stream.send("printf 'SIZE_OK_'; stty size\r".toByteArray()))
            resizeConfirmed.await()
            val waiting=StringBuilder()
            val foregroundReady=async {withTimeout(15_000){attached.output.first {bytes->waiting.append(bytes.toString(Charsets.UTF_8));waiting.contains("WAIT_READY\r\n")}}}
            assertTrue(stream.send("printf 'WAIT_READY\\n'; sleep 30; printf 'WAIT_DONE\\n'\r".toByteArray()))
            foregroundReady.await()
            val interrupted=StringBuilder()
            val interruption=async {withTimeout(10_000){attached.output.first {bytes->interrupted.append(bytes.toString(Charsets.UTF_8));interrupted.contains("INTERRUPTED_OK\r\n")}}}
            assertTrue(stream.send(byteArrayOf(3)))
            delay(100)
            assertTrue(stream.send("printf 'INTERRUPTED_OK\\n'\r".toByteArray()))
            interruption.await()
            assertFalse(interrupted.contains("WAIT_DONE"))
            val second=TerminalStream(ssh,scope)
            try {
                second.attach(socket,session.getString("id"),session.getString("stamp"),72,20)
                withTimeout(5_000) {while((client.request("sessions") as JSONArray).getJSONObject(0).getInt("attached")!=2)delay(50)}
                assertTrue(attached.connected)
            } finally {second.close()}
            // Management requests remain usable while the terminal is attached.
            assertEquals(1,(client.request("sessions") as JSONArray).length())
            stream.close();stream=null
            assertEquals(1,(client.request("sessions") as JSONArray).length())
            client.request("rename",JSONObject(target.toString()).put("name",args.getString("name")+"-renamed"))
            val settings=client.request("tmux_settings_read",JSONObject().put("session",session.getString("id")).put("stamp",session.getString("stamp"))) as JSONObject
            assertTrue(settings.getString("revision").isNotBlank())
            client.request("destroy",target)
            try{client.request("session_context",target);fail("Expected missing target rejection")}catch(e:Exception){assertTrue(e.message.orEmpty().contains("Session no longer exists"))}
        }finally{stream?.close();client.close();other.close();ssh.disconnect();trust.cancelAndJoin();scope.cancel();known.delete()}
    }
}

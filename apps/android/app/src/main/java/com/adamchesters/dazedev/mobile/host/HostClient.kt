package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.ssh.SshConnection
import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.jcraft.jsch.ChannelExec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import org.json.JSONObject

class HostClient(private val ssh: SshConnection, private val scope: CoroutineScope) {
    private var channel: ChannelExec? = null
    private var reader: Job? = null
    private val writer = Mutex()
    private val requests=Semaphore(8)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicLong()
    private var ready = CompletableDeferred<JSONObject>()
    private var nonce = ""

    suspend fun start(source: ByteArray, socket: String): JSONObject {
        close()
        nonce = UUID.randomUUID().toString().replace("-", "")
        ready = CompletableDeferred()
        val loader = "import base64,sys;print('DAZED:'+sys.argv[1]+':bootstrap',flush=True);exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))"
        val cmd = "export DAZED_TMUX_SOCKET=${ShellQuoter.quote(socket)}; exec python3 -u -c ${ShellQuoter.quote(loader)} $nonce"
        val active = ssh.openStream(cmd)
        val input = active.inputStream
        val output = active.outputStream
        active.setErrStream(object : java.io.OutputStream() { override fun write(value: Int) {} })
        channel = active
        withContext(Dispatchers.IO) { active.connect(15_000) }
        val runNonce = nonce
        val handshake = ready
        reader = scope.launch(Dispatchers.IO) {
            var bootstrapped=false
        val frames = HostFrames(runNonce)
            try {
                val buffer = StringBuilder()
                while (isActive && channel === active) {
                    val ch = input.read()
                    if (ch == -1) error("Host stream closed. Reconnect to continue.")
                    if (ch == 10) {
                        val line = buffer.toString().trimEnd('\r'); buffer.setLength(0)
                        if (line == "DAZED:$runNonce:bootstrap" && !bootstrapped) {
                            bootstrapped=true
                            writer.withLock { output.write(Base64.getEncoder().encode(source));output.write(10);output.flush() }
                        } else frames.line(line)?.let { text ->
                            val reply = JSONObject(text)
                            val id = reply.getString("id")
                            if (id == "ready") handshake.complete(reply.getJSONObject("result"))
                            else pending.remove(id)?.complete(reply)
                        }
                    } else {
                        require(buffer.length < 4096) { "Host protocol line too long" }
                        buffer.append(ch.toChar())
                    }
                }
            } catch (e: Exception) {
                handshake.completeExceptionally(e)
                if (channel === active) {pending.values.forEach { it.completeExceptionally(e) }; pending.clear()}
            } finally { active.disconnect() }
        }
        return try { withTimeout(20_000) { ready.await() } } catch (e: Exception) { close();throw e }
    }

    suspend fun request(method: String, args: JSONObject = JSONObject(), timeoutMs: Long = 60_000): Any {
        val active = channel?.takeIf { it.isConnected } ?: error("Reconnect this host first.")
        require(requests.tryAcquire()) { "Host is busy. Wait for the current operation." }
        val id = ids.incrementAndGet().toString()
        val deferred = CompletableDeferred<JSONObject>();pending[id] = deferred
        return try {
            val request = JSONObject().put("id",id).put("method",method).put("args",args).toString().toByteArray()
            require(request.size <= 1_048_576) { "Host request too large" }
            withContext(Dispatchers.IO) {
                writer.withLock { val output=active.outputStream;output.write(Base64.getEncoder().encode(request));output.write(10);output.flush() }
            }
            val reply = withTimeout(timeoutMs) { deferred.await() }
            if (reply.has("error")) error(reply.getString("error"))
            reply.get("result")
        } finally { pending.remove(id);deferred.cancel();requests.release() }
    }

    fun close() {
        reader?.cancel();reader = null
        channel?.disconnect();channel = null
        pending.values.forEach { it.cancel() }; pending.clear()
        ready.cancel()
    }
}

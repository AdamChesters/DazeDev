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

class HostClient(private val ssh: SshConnection, private val scope: CoroutineScope,
    private val onBrowserOpen: suspend (String)->String? = { "Browser unavailable" }) {
    private var channel: ChannelExec? = null
    private var reader: Job? = null
    private val writer = Mutex()
    private val requests=Semaphore(8)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicLong()
    private var ready = CompletableDeferred<JSONObject>()
    private var nonce = ""
    private val browserEndpoint=UUID.randomUUID().toString().replace("-", "")
    private val browserDevice=UUID.randomUUID().toString().replace("-", "")
    private val browserJobs=ConcurrentHashMap<String,Job>()

    suspend fun start(source: ByteArray, socket: String, browserSource: ByteArray = ByteArray(0)): JSONObject {
        close()
        nonce = UUID.randomUUID().toString().replace("-", "")
        ready = CompletableDeferred()
        val loader = "import base64,sys;print('DAZED:'+sys.argv[1]+':bootstrap',flush=True);exec(compile(base64.b64decode(sys.stdin.buffer.readline().strip(),validate=True),'<dazedev-host>','exec'))"
        val browserEnvironment=if(browserSource.isNotEmpty())"export DAZED_BROWSER_ENDPOINT=$browserEndpoint DAZED_BROWSER_DESKTOP=$browserDevice; " else ""
        val payload=if(browserSource.isNotEmpty()) {
            ("import base64\nBROWSER_SOURCE=base64.b64decode('${Base64.getEncoder().encodeToString(browserSource)}').decode()\n".toByteArray()+source)
        } else source
        val cmd = "export DAZED_TMUX_SOCKET=${ShellQuoter.quote(socket)}; ${browserEnvironment}exec python3 -u -c ${ShellQuoter.quote(loader)} $nonce"
        val active = ssh.openStream(cmd)
        val input = java.io.BufferedInputStream(active.inputStream, 65536)
        val output = active.outputStream
        active.setErrStream(object : java.io.OutputStream() { override fun write(value: Int) {} })
        channel = active
        withContext(Dispatchers.IO) { active.connect(15_000) }
        val runNonce = nonce
        val handshake = ready
        val browserIds=LinkedHashSet<String>()
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
                            writer.withLock { output.write(Base64.getEncoder().encode(payload));output.write(10);output.flush() }
                        } else frames.line(line)?.let { text ->
                            val reply = JSONObject(text)
                            if(reply.optString("event")=="browser-open") {
                                val open=BrowserFrames.parse(reply) ?: return@let
                                if(channel!==active || !active.isConnected || !handshake.isCompleted || handshake.isCancelled || open.id in browserIds || browserJobs.size>=8)return@let
                                browserIds.add(open.id)
                                if(browserIds.size>128)browserIds.remove(browserIds.first())
                                val job=scope.launch(start=CoroutineStart.LAZY) {
                                    try {
                                        val failure=withContext(Dispatchers.Main) {
                                            if(channel!==active || !active.isConnected)"Browser connection expired"
                                            else try {onBrowserOpen(open.url)} catch(e:CancellationException) {throw e}
                                            catch(e:Exception) {"Could not open the phone browser"}
                                        }
                                        if(channel===active && active.isConnected) {
                                            val result=JSONObject().put("id",open.id)
                                            if(failure!=null)result.put("error",failure.take(1024))
                                            request("browser_result",result,10_000)
                                        }
                                    } catch(e:CancellationException) {throw e}
                                    catch(_:Exception) { }
                                    finally {browserJobs.remove(open.id)}
                                }
                                browserJobs[open.id]=job;job.start()
                                return@let
                            }
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
        browserJobs.values.forEach {it.cancel()};browserJobs.clear()
        reader?.cancel();reader = null
        channel?.disconnect();channel = null
        pending.values.forEach { it.cancel() }; pending.clear()
        ready.cancel()
    }
}

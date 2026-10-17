package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.ssh.SshLink
import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class HostClient(
    private val ssh: SshLink,
    private val scope: CoroutineScope,
    private val onBrowserOpen: suspend (String) -> String? = { "Browser unavailable" },
    private val onLinkLost: (String) -> Unit = {},
) : HostLink {
    private val linkEvents = MutableSharedFlow<HostLinkEvent>(extraBufferCapacity = 8)
    override val events: SharedFlow<HostLinkEvent> = linkEvents.asSharedFlow()

    @Volatile private var channel: ChannelExec? = null
    private var reader: Job? = null
    private val writer = Mutex()
    private val requests = Semaphore(8)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val ids = AtomicLong()
    private var ready = CompletableDeferred<JSONObject>()
    private var nonce = ""
    private val browserEndpoint = UUID.randomUUID().toString().replace("-", "")
    private val browserDevice = UUID.randomUUID().toString().replace("-", "")
    private val browserJobs = ConcurrentHashMap<String, Job>()
    override val isOpen get() = channel?.isConnected == true

    override suspend fun start(
        source: ByteArray,
        socket: String,
        browserSource: ByteArray,
        host: String,
    ): JSONObject {
        close()
        nonce = UUID.randomUUID().toString().replace("-", "")
        ready = CompletableDeferred()
        val browserEnvironment = if (browserSource.isNotEmpty()) "export DAZED_BROWSER_ENDPOINT=$browserEndpoint DAZED_BROWSER_DESKTOP=$browserDevice; " else ""
        val payload =
            if (browserSource.isNotEmpty()) {
                ("import base64\nBROWSER_SOURCE=base64.b64decode('${Base64.getEncoder().encodeToString(browserSource)}').decode()\n".toByteArray() + source)
            } else {
                source
            }
        // -I keeps stray modules in the home or working folder from loading.
        val cmd = "export DAZED_TMUX_SOCKET=${ShellQuoter.quote(socket)}; ${browserEnvironment}exec python3 -I -u -c ${ShellQuoter.quote(HostLoader.script)} $nonce 2"
        val active = ssh.openStream(cmd)
        val input = active.inputStream
        val output = active.outputStream
        val stderr = TailBuffer()
        active.setErrStream(stderr)
        channel = active
        val runNonce = nonce
        val handshake = ready
        val browserIds = LinkedHashSet<String>()
        try {
            withContext(Dispatchers.IO) { active.connect(15_000) }
        } catch (e: Exception) {
            close()
            throw e
        }
        reader =
            scope.launch(Dispatchers.IO) {
                var bootstrapped = false
                // Text lines until the helper source is sent, then protocol 2 frames.
                var frames: MessageFrames? = null
                val lines = HostLines()
                var failure: Exception? = null
                var linked = false

                fun line(line: String): Boolean {
                    if (line.startsWith("DAZED:$runNonce:python:")) {
                        HostLoader.tooOld(line, runNonce)?.let { error("DazeDev needs Python 3.9 or newer on $host (found $it).") }
                        return true
                    }
                    if (line == "DAZED:$runNonce:no-tmux") error("DazeDev needs tmux on $host. Install tmux, then connect again.")
                    if (line != "DAZED:$runNonce:bootstrap") return true
                    bootstrapped = true
                    // No request can be written before the ready reply, so this needs no lock.
                    output.write(Base64.getEncoder().encode(payload))
                    output.write(10)
                    output.flush()
                    frames = MessageFrames()
                    return false
                }

                fun handle(message: ByteArray) {
                    val reply = JSONObject(String(message, Charsets.UTF_8))
                    if (reply.optString("event") == "browser-open") {
                        val open = BrowserFrames.parse(reply) ?: return
                        if (channel !== active || !active.isConnected || !handshake.isCompleted || handshake.isCancelled || open.id in browserIds || browserJobs.size >= 8) return
                        browserIds.add(open.id)
                        if (browserIds.size > 128) browserIds.remove(browserIds.first())
                        val job =
                            scope.launch(start = CoroutineStart.LAZY) {
                                try {
                                    val failed =
                                        withContext(Dispatchers.Main) {
                                            if (channel !== active || !active.isConnected) {
                                                "Browser connection expired"
                                            } else {
                                                try {
                                                    onBrowserOpen(open.url)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    "Could not open the phone browser"
                                                }
                                            }
                                        }
                                    if (channel === active && active.isConnected) {
                                        val result = JSONObject().put("id", open.id)
                                        if (failed != null) result.put("error", failed.take(1024))
                                        request("browser_result", result, 10_000)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                } finally {
                                    browserJobs.remove(open.id)
                                }
                            }
                        browserJobs[open.id] = job
                        job.start()
                        return
                    }
                    val id = reply.getString("id")
                    if (id == "ready") {
                        val result = reply.getJSONObject("result")
                        val protocol = result.optInt("protocol", 1)
                        require(protocol == 2) { "The DazeDev helper on $host answered with protocol $protocol; this app needs protocol 2." }
                        linked = true
                        handshake.complete(result)
                    } else {
                        pending.remove(id)?.complete(reply)
                    }
                }
                try {
                    val buffer = ByteArray(16384)
                    while (isActive && channel === active) {
                        val count = input.read(buffer)
                        if (count < 0) {
                            // The exit status arrives just after end of stream.
                            repeat(20) { if (!active.isClosed) delay(50) }
                            error(HostLoader.closedMessage(stderr.text(), active.exitStatus, host))
                        }
                        var used = 0
                        if (!bootstrapped) used = lines.feed(buffer, count, ::line)
                        frames?.let { decoder -> if (used < count) decoder.push(buffer, used, count - used).forEach(::handle) }
                    }
                } catch (e: CancellationException) {
                    failure = e
                } catch (e: Exception) {
                    failure = e
                } finally {
                    val error = failure ?: IllegalStateException("Host stream closed. Reconnect to continue.")
                    handshake.completeExceptionally(error)
                    if (channel === active) {
                        channel = null
                        pending.values.forEach { it.completeExceptionally(error) }
                        pending.clear()
                        if (linked && error !is CancellationException) {
                            val reason = error.message ?: "Host stream closed."
                            onLinkLost(reason)
                            linkEvents.tryEmit(HostLinkEvent.Lost(reason))
                        }
                    }
                    active.disconnect()
                }
            }
        return try {
            withTimeoutOrNull(20_000) { handshake.await() }
                ?: error(
                    HostLoader.closedMessage(stderr.text(), -1, host).let {
                        if (it.startsWith("Host stream closed")) "$host did not start the DazeDev helper within 20 s." else it
                    },
                )
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    override suspend fun request(
        method: String,
        args: JSONObject,
        timeoutMs: Long,
    ): Any = send(method, args, timeoutMs, limited = true)

    // The liveness check skips the request limit: requests stuck on a dead link must not hide it.
    override suspend fun probe(timeoutMs: Long) {
        send("ping", JSONObject(), timeoutMs, limited = false)
    }

    private suspend fun send(
        method: String,
        args: JSONObject,
        timeoutMs: Long,
        limited: Boolean,
    ): Any {
        val active = channel?.takeIf { it.isConnected } ?: error("Reconnect this host first.")
        require(!limited || requests.tryAcquire()) { "Host is busy. Wait for the current operation." }
        val id = ids.incrementAndGet().toString()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        return try {
            val request =
                JSONObject()
                    .put("id", id)
                    .put("method", method)
                    .put("args", args)
                    .toString()
                    .toByteArray()
            require(request.size <= 1_048_576) { "Host request too large" }
            withContext(Dispatchers.IO) {
                writer.withLock {
                    val output = active.outputStream
                    output.write(MessageFrames.request(request))
                    output.flush()
                }
            }
            // A timeout is a failure the caller must show, not a silent cancellation.
            val reply = withTimeoutOrNull(timeoutMs) { deferred.await() } ?: throw HostTimeoutException(method, timeoutMs)
            MessageFrames.errorText(reply.opt("error"))?.let { error(it) }
            reply.get("result")
        } finally {
            pending.remove(id)
            deferred.cancel()
            if (limited) requests.release()
        }
    }

    override fun close() {
        browserJobs.values.forEach { it.cancel() }
        browserJobs.clear()
        val old = channel
        channel = null
        reader?.cancel()
        reader = null
        old?.disconnect()
        pending.values.forEach { it.cancel() }
        pending.clear()
        ready.cancel()
    }
}

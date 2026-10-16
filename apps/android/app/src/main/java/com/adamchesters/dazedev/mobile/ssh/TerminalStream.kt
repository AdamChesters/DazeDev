package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.adamchesters.dazedev.mobile.tmux.TerminalScrollCommands
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.selects.select
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class TerminalStream(
    private val ssh: SshLink,
    parent: CoroutineScope,
) : TerminalLink {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var active: ChannelExec? = null
    private val closing = AtomicBoolean(false)

    // Input waits in a byte-bounded queue; only a dead stream refuses it.
    private val outgoing = Channel<ByteArray>(Channel.UNLIMITED)
    private val queuedInput = AtomicLong(0)
    private val sizes = Channel<Pair<Int, Int>>(Channel.CONFLATED)

    // Output is drained from SSH at once so a slow renderer never fills JSch's
    // shared pipe and stalls other channels on the same connection.
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val bufferedOutput = AtomicLong(0)
    private val drained = Channel<Unit>(Channel.CONFLATED)
    override val output =
        incoming.receiveAsFlow().onEach {
            bufferedOutput.addAndGet(-it.size.toLong())
            drained.trySend(Unit)
        }
    private val _connected = MutableStateFlow(false)
    override val state = _connected.asStateFlow()
    override val connected get() = _connected.value

    @Volatile private var attached: Triple<String, String, String>? = null

    suspend fun attach(
        socket: String,
        id: String,
        stamp: String,
        cols: Int,
        rows: Int,
        browser: JSONObject? = null,
        ignoreSize: Boolean = false,
    ) {
        require(Regex("\\$[0-9]+").matches(id) && stamp.isNotBlank()) { "Refresh this session before attaching." }
        val script = "import subprocess,sys,os; a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else []); s=subprocess.check_output(a+['display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}'],text=True).strip(); assert s==sys.argv[3], 'Session ended or restarted. Go back and open it again.'; os.execvp('tmux',a+['-u','attach-session']+(['-f','ignore-size'] if sys.argv[4]=='1' else [])+['-t',sys.argv[2]])"
        val attachCommand = "exec python3 -I -u -c ${ShellQuoter.quote(script)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(id)} ${ShellQuoter.quote(stamp)} ${if (ignoreSize) "1" else "0"}"
        val command =
            if (browser == null) {
                attachCommand
            } else {
                val executable = browser.getString("command")
                val endpoint = browser.getString("endpoint")
                require(executable.startsWith("/") && executable.endsWith("/xdg-open") && executable.none { it.code < 32 }) { "Invalid browser bridge command." }
                require(Regex("[a-f0-9]{32}").matches(endpoint)) { "Invalid browser bridge endpoint." }
                val directory = executable.substringBeforeLast('/')
                "export BROWSER=${ShellQuoter.quote(executable)}; export PATH=${ShellQuoter.quote(directory)}:\"\$PATH\"; " +
                    "export DAZEDEV_BROWSER_TTY=\"\$(tty)\" DAZEDEV_BROWSER_PID=\"\$\$\"; " +
                    "\"\$BROWSER\" --dazedev-register ${ShellQuoter.quote(endpoint)} \"\$\$\" \"\$DAZEDEV_BROWSER_TTY\" || printf '%s\\n' 'Browser forwarding is unavailable. Tap terminal links to open them.' >&2; $attachCommand"
            }
        val channel = ssh.openStream(command, true, cols.coerceIn(2, 500), rows.coerceIn(2, 300))
        val input = channel.inputStream
        val output = channel.outputStream
        active = channel
        try {
            withContext(Dispatchers.IO) { channel.connect(15_000) }
        } catch (e: Exception) {
            close()
            channel.disconnect()
            active = null
            scope.cancel()
            throw e
        }
        if (closing.get()) {
            channel.disconnect()
            active = null
            scope.cancel()
            return
        }
        attached = Triple(socket, id, stamp)
        _connected.value = true
        // One owner completes each packet before resize or disconnect can run.
        scope.launch(Dispatchers.IO) {
            try {
                while (isActive && !closing.get()) {
                    select<Unit> {
                        sizes.onReceiveCatching { result ->
                            val size = result.getOrNull()
                            if (size != null && !closing.get()) channel.setPtySize(size.first, size.second, 0, 0)
                        }
                        outgoing.onReceiveCatching { result ->
                            val bytes = result.getOrNull()
                            if (bytes != null) {
                                queuedInput.addAndGet(-bytes.size.toLong())
                                if (!closing.get()) {
                                    output.write(bytes)
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                incoming.close(e)
            } finally {
                close()
                channel.disconnect()
                active = null
                scope.cancel()
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(32768)
                while (isActive && !closing.get()) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    // Past this much unrendered output, wait rather than use unbounded memory.
                    while (bufferedOutput.get() > MAX_BUFFERED_OUTPUT && !closing.get())withTimeoutOrNull(250) { drained.receive() }
                    bufferedOutput.addAndGet(size.toLong())
                    incoming.send(buffer.copyOf(size))
                }
            } catch (e: Exception) {
                incoming.close(e)
            } finally {
                close()
            }
        }
    }

    /** False when the stream is closed or too much input is already waiting. */
    override fun send(bytes: ByteArray): Boolean {
        if (!connected || closing.get() || bytes.size !in 1..65_536) return false
        if (queuedInput.addAndGet(bytes.size.toLong()) > MAX_QUEUED_INPUT) {
            queuedInput.addAndGet(-bytes.size.toLong())
            return false
        }
        return outgoing.trySend(bytes.copyOf()).isSuccess.also { if (!it) queuedInput.addAndGet(-bytes.size.toLong()) }
    }

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        if (connected && !closing.get()) sizes.trySend(cols.coerceIn(2, 500) to rows.coerceIn(2, 300))
    }

    /** Leaves tmux copy mode on this session's active pane, if it is in it. */
    override suspend fun cancelCopyMode() {
        val target = attached ?: return
        ssh
            .execute(TerminalScrollCommands.cancel(target.first, target.second, target.third), 15_000)
            .requireSuccess("Returning to live terminal output")
    }

    private companion object {
        const val MAX_QUEUED_INPUT = 1L shl 20
        const val MAX_BUFFERED_OUTPUT = 8L shl 20
    }

    override fun close() {
        if (closing.compareAndSet(false, true)) {
            _connected.value = false
            outgoing.cancel()
            sizes.cancel()
            incoming.close()
        }
    }
}

package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.adamchesters.dazedev.mobile.tmux.TerminalScrollCommands
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

class TerminalStream(private val ssh: SshConnection, parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var active: ChannelExec? = null
    private val closing=AtomicBoolean(false)
    private val outgoing = Channel<ByteArray>(32)
    private val sizes = Channel<Pair<Int,Int>>(Channel.CONFLATED)
    private val incoming = Channel<ByteArray>(8)
    val output = incoming.receiveAsFlow()
    private val _connected=MutableStateFlow(false)
    val state = _connected.asStateFlow()
    val connected get() = _connected.value
    private val scrollWake = Channel<Unit>(Channel.CONFLATED)
    private val pendingScroll = AtomicInteger(0)
    private val pendingBottom = AtomicBoolean(false)
    private val failures = Channel<String>(Channel.CONFLATED)
    val scrollFailures = failures.receiveAsFlow()
    suspend fun attach(socket: String, id: String, stamp: String, cols: Int, rows: Int, browser: JSONObject? = null) {
        require(Regex("\\$[0-9]+").matches(id) && stamp.isNotBlank()) { "Refresh this session before attaching." }
        val script = "import subprocess,sys,os; a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else []); s=subprocess.check_output(a+['display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}'],text=True).strip(); assert s==sys.argv[3], 'Session changed; refresh before reconnecting'; os.execvp('tmux',a+['attach-session','-t',sys.argv[2]])"
        val attachCommand = "exec python3 -c ${ShellQuoter.quote(script)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(id)} ${ShellQuoter.quote(stamp)}"
        val command = if(browser==null) attachCommand else {
            val executable=browser.getString("command")
            val endpoint=browser.getString("endpoint")
            require(executable.startsWith("/") && executable.endsWith("/xdg-open") && executable.none {it.code<32}) {"Invalid browser bridge command."}
            require(Regex("[a-f0-9]{32}").matches(endpoint)) {"Invalid browser bridge endpoint."}
            val directory=executable.substringBeforeLast('/')
            "export BROWSER=${ShellQuoter.quote(executable)}; export PATH=${ShellQuoter.quote(directory)}:\"\$PATH\"; " +
                "export DAZEDEV_BROWSER_TTY=\"\$(tty)\" DAZEDEV_BROWSER_PID=\"\$\$\"; " +
                "\"\$BROWSER\" --dazedev-register ${ShellQuoter.quote(endpoint)} \"\$\$\" \"\$DAZEDEV_BROWSER_TTY\" || printf '%s\\n' 'Browser forwarding is unavailable. Tap terminal links to open them.' >&2; $attachCommand"
        }
        val channel=ssh.openStream(command,true,cols.coerceIn(2,500),rows.coerceIn(2,300))
        val input=channel.inputStream;val output=channel.outputStream
        active=channel
        try { withContext(Dispatchers.IO) {channel.connect(15_000)} }
        catch(e:Exception) {close();channel.disconnect();active=null;scope.cancel();throw e}
        if(closing.get()){channel.disconnect();active=null;scope.cancel();return}
        _connected.value=true
        scope.launch {
            for (ignored in scrollWake) {
                val bottom=pendingBottom.getAndSet(false)
                val lines=pendingScroll.getAndSet(0)
                if(!connected || closing.get())break
                if(!bottom && lines==0)continue
                try {
                    if(bottom)ssh.execute(TerminalScrollCommands.command(socket,id,stamp,0,true),15_000)
                        .requireSuccess("Returning to live terminal output")
                    if(lines!=0)ssh.execute(TerminalScrollCommands.command(socket,id,stamp,lines),15_000)
                        .requireSuccess("Scrolling terminal history")
                } catch(e:CancellationException) {throw e}
                catch(e:Exception) {failures.trySend(e.message?:"Could not scroll terminal history. Reconnect to retry.")}
            }
        }
        // One owner completes each packet before resize or disconnect can run.
        scope.launch(Dispatchers.IO) {
            try {
                while(isActive && !closing.get()) {
                    select<Unit> {
                        sizes.onReceiveCatching {result->
                            val size=result.getOrNull()
                            if(size!=null && !closing.get())channel.setPtySize(size.first,size.second,0,0)
                        }
                        outgoing.onReceiveCatching {result->
                            val bytes=result.getOrNull()
                            if(bytes!=null && !closing.get()){output.write(bytes);output.flush()}
                        }
                    }
                }
            } catch(e:Exception) {incoming.close(e)}
            finally {close();channel.disconnect();active=null;scope.cancel()}
        }
        scope.launch(Dispatchers.IO) {
            try {
                val buffer=ByteArray(8192)
                while(isActive && !closing.get()) {val size=input.read(buffer);if(size<0) break;incoming.send(buffer.copyOf(size))}
            } catch(e:Exception) {incoming.close(e)} finally {close()}
        }
    }
    fun send(bytes: ByteArray): Boolean = connected && !closing.get() && bytes.size in 1..65_536 && outgoing.trySend(bytes.copyOf()).isSuccess
    fun resize(cols: Int, rows: Int) {if(connected && !closing.get())sizes.trySend(cols.coerceIn(2,500) to rows.coerceIn(2,300))}
    fun scrollHistory(lines: Int): Boolean {
        if(!connected || closing.get())return false
        if(lines==0)return true
        pendingScroll.updateAndGet { (it.toLong()+lines.toLong()).coerceIn(-5000L,5000L).toInt() }
        return scrollWake.trySend(Unit).isSuccess
    }
    fun scrollToBottom(): Boolean {
        if(!connected || closing.get())return false
        pendingScroll.set(0);pendingBottom.set(true)
        return scrollWake.trySend(Unit).isSuccess
    }
    fun close() {
        if(closing.compareAndSet(false,true)) {
            _connected.value=false;outgoing.cancel();sizes.cancel();incoming.close()
            scrollWake.cancel();failures.close()
        }
    }
}

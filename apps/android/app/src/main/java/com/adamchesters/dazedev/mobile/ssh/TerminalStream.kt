package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import java.util.concurrent.atomic.AtomicBoolean

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
    suspend fun attach(socket: String, id: String, stamp: String, cols: Int, rows: Int) {
        require(Regex("\\$[0-9]+").matches(id) && stamp.isNotBlank()) { "Refresh this session before attaching." }
        val script = "import subprocess,sys,os; a=['tmux']+(['-L',sys.argv[1]] if sys.argv[1] else []); s=subprocess.check_output(a+['display-message','-p','-t',sys.argv[2],'#{pid}:#{start_time}:#{session_created}'],text=True).strip(); assert s==sys.argv[3], 'Session changed; refresh before reconnecting'; os.execvp('tmux',a+['attach-session','-t',sys.argv[2]])"
        val command = "exec python3 -c ${ShellQuoter.quote(script)} ${ShellQuoter.quote(socket)} ${ShellQuoter.quote(id)} ${ShellQuoter.quote(stamp)}"
        val channel=ssh.openStream(command,true,cols.coerceIn(2,500),rows.coerceIn(2,300))
        val input=channel.inputStream;val output=channel.outputStream
        active=channel
        try { withContext(Dispatchers.IO) {channel.connect(15_000)} }
        catch(e:Exception) {close();channel.disconnect();active=null;scope.cancel();throw e}
        if(closing.get()){channel.disconnect();active=null;scope.cancel();return}
        _connected.value=true
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
    fun close() {
        if(closing.compareAndSet(false,true)) {
            _connected.value=false;outgoing.cancel();sizes.cancel();incoming.close()
        }
    }
}

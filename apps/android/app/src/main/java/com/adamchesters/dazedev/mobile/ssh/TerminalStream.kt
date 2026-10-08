package com.adamchesters.dazedev.mobile.ssh

import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class TerminalStream(private val ssh: SshConnection, parent: CoroutineScope) {
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
    private var active: ChannelExec? = null
    private val outgoing = Channel<ByteArray>(32)
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
        try { withContext(Dispatchers.IO) {channel.connect(15_000)} } catch(e:Exception) {close();throw e}
        _connected.value=true
        scope.launch(Dispatchers.IO) {
            try { for(bytes in outgoing) {output.write(bytes);output.flush()} }
            catch(e:Exception) {incoming.close(e)}
            finally {close()}
        }
        scope.launch(Dispatchers.IO) {
            try {
                val buffer=ByteArray(8192)
                while(isActive) {val size=input.read(buffer);if(size<0) break;incoming.send(buffer.copyOf(size))}
            } catch(e:Exception) {incoming.close(e)} finally {close()}
        }
    }
    fun send(bytes: ByteArray): Boolean = connected && bytes.size in 1..65_536 && outgoing.trySend(bytes.copyOf()).isSuccess
    fun resize(cols: Int, rows: Int) {if(connected) active?.setPtySize(cols.coerceIn(2,500),rows.coerceIn(2,300),0,0)}
    fun close() {_connected.value=false;active?.disconnect();active=null;outgoing.close();incoming.close();scope.cancel()}
}

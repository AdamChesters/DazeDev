package com.adamchesters.dazedev.mobile.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.net.toUri
import com.adamchesters.dazedev.mobile.data.*
import com.adamchesters.dazedev.mobile.host.*
import com.adamchesters.dazedev.mobile.ssh.*
import com.adamchesters.dazedev.mobile.tmux.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

enum class ConnectionPhase { Disconnected, Connecting, Connected }
data class TmuxWindow(val id:String,val name:String,val active:Boolean)
data class UserMessage(val id: Long, val text: String)
data class HostSummary(val profile: ConnectionProfile, val phase: ConnectionPhase, val workspaceCount: Int, val fingerprint: String = "", val folders:List<WorkspaceFolder> = emptyList())
data class DazeDevMobileUiState(
    val profile: ConnectionProfile = ConnectionProfile(),
    val hosts: List<HostSummary> = emptyList(),
    val showHosts: Boolean = true,
    val connectionPhase: ConnectionPhase = ConnectionPhase.Disconnected,
    val folders: List<WorkspaceFolder> = emptyList(),
    val selectedFolder: String? = null,
    val sessions: List<TmuxSession> = emptyList(),
    val repositories: List<String> = emptyList(),
    val agents: List<AgentCapability> = emptyList(),
    val selectedSessionName: String? = null,
    val selectedSessionId: String? = null,
    val selectedStamp: String? = null,
    val selectedTargetHost: String? = null,
    val selectedTargetLabel: String = "",
    val terminal: TerminalStream? = null,
    val terminalEpoch: Long = 0,
    val tmuxSettings: JSONObject? = null,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isCreating: Boolean = false,
    val endingSessionName: String? = null,
    val hostKeyChallenge: HostKeyChallenge? = null,
    val challengeHost: String = "",
    val challengeHostId: String = "",
    val conflict: Boolean = false,
    val recoveryDraft: Boolean = false,
    val draftReview: JSONObject? = null,
    val windows: List<TmuxWindow> = emptyList(),
    val message: UserMessage? = null,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store=ProfileStore(application)
    private val ids=AtomicLong(1)
    private val _uiState=MutableStateFlow(DazeDevMobileUiState(profile=store.load()))
    val uiState: StateFlow<DazeDevMobileUiState> = _uiState.asStateFlow()
    private var foreground=true
    private var attachment: Job?=null
    private var attachmentGeneration=0L
    private val hosts=linkedMapOf<String, HostState>()
    private val challenges=linkedMapOf<String,HostKeyChallenge>()
    private fun publishChallenge(){val first=challenges.entries.firstOrNull();_uiState.update {it.copy(hostKeyChallenge=first?.value,challengeHostId=first?.key.orEmpty(),challengeHost=hosts[first?.key]?.profile?.host.orEmpty())}}
    private inner class HostState(var profile: ConnectionProfile) {
        val prompts=HostKeyPromptCoordinator()
        val ssh=SshConnection(File(getApplication<Application>().filesDir,"ssh/${profile.id}/known_hosts"),prompts)
        val client=HostClient(ssh,viewModelScope)
        var phase=ConnectionPhase.Disconnected
        var shared: SharedProfile?=null
        var fingerprint=""
        var sessions=listOf<TmuxSession>()
        var repositories=listOf<String>()
        var agents=listOf<AgentCapability>()
        var conflict=false
        var refreshing=false
        var creating=false
        var ending:String?=null
        var connectJob:Job?=null
        val edits=Mutex()
        val promptJob=viewModelScope.launch {
            prompts.challenge.collect { challenge -> if(challenge==null)challenges.remove(profile.id)else challenges[profile.id]=challenge;publishChallenge() }
        }
    }
    init {store.loadAll().forEach {hosts[it.id]=HostState(it)};publishHosts()}
    private fun current(): HostState? = hosts[_uiState.value.profile.id]
    private fun publishHosts() { _uiState.update {it.copy(hosts=hosts.values.map {h->HostSummary(h.profile,h.phase,h.shared?.folders()?.size?:0,h.fingerprint,h.shared?.folders().orEmpty())})} }
    private fun publish(h: HostState) {
        publishHosts()
        if (_uiState.value.profile.id != h.profile.id) return
        _uiState.update {it.copy(profile=h.profile,connectionPhase=h.phase,folders=h.shared?.folders().orEmpty(),sessions=h.sessions,repositories=h.repositories,agents=h.agents,conflict=h.conflict,recoveryDraft=store.draft(h.profile.id)!=null,isRefreshing=h.refreshing,isCreating=h.creating,endingSessionName=h.ending)}
    }
    fun showHosts() {closeTerminal();_uiState.update {it.copy(showHosts=true,selectedFolder=null,selectedSessionName=null,selectedSessionId=null)}}
    fun newHost() {closeTerminal();_uiState.update {it.copy(profile=ConnectionProfile(),showHosts=false,connectionPhase=ConnectionPhase.Disconnected,selectedFolder=null,selectedSessionName=null)}}
    fun selectHost(id: String) {
        val h=hosts[id]?:return;closeTerminal()
        _uiState.update {it.copy(profile=h.profile,showHosts=false,selectedFolder=null,selectedSessionName=null,selectedSessionId=null)};publish(h)
    }
    fun selectFolder(id: String?) {_uiState.update {it.copy(selectedFolder=id)}}
    fun setPrivateKey(uri: Uri, displayName: String) {_uiState.update {it.copy(profile=it.profile.copy(privateKeyUri=uri.toString(),privateKeyName=displayName))}}
    fun connect(profile: ConnectionProfile, secret: String) {
        val normalized=profile.copy(host=profile.host.trim(),username=profile.username.trim(),workingDirectory=profile.workingDirectory.trim(),socket=profile.socket.trim())
        normalized.validationError(secret)?.let {postMessage(it);return}
        val h=hosts.getOrPut(normalized.id){HostState(normalized)}
        if(h.connectJob?.isCompleted==false){postMessage("Wait for this host connection to finish or cancel.");return}
        h.profile=normalized;h.phase=ConnectionPhase.Connecting;publish(h)
        h.connectJob=viewModelScope.launch {
            var key:ByteArray?=null
            try {
                if(normalized.authMethod==AuthMethod.PrivateKey)key=readPrivateKey(requireNotNull(normalized.privateKeyUri))
                h.ssh.connect(normalized,secret,key)
                val source=withContext(Dispatchers.IO){getApplication<Application>().assets.open("host.py").use {it.readBytes()}}
                val ready=h.client.start(source,normalized.socket)
                h.fingerprint="${ready.getString("machine")}:${ready.getString("user")}:${normalized.socket}"
                val shared=SharedProfile(h.client.request("profile_load") as JSONObject)
                shared.rebindOwner(normalized.id,h.fingerprint);h.shared=shared
                h.phase=ConnectionPhase.Connected;store.save(normalized);publish(h)
                val capabilities=h.client.request("capabilities") as JSONObject
                val advertised=capabilities.getJSONArray("agents")
                h.agents=(0 until advertised.length()).map {index->
                    val agent=advertised.getJSONObject(index);val models=agent.getJSONArray("models")
                    val efforts=(0 until models.length()).associate {i->val m=models.getJSONObject(i);val e=m.getJSONArray("efforts");m.getString("id") to (0 until e.length()).map {e.getString(it)}}
                    AgentCapability(agent.getString("id"),agent.optString("version"),efforts.keys.toList(),efforts,agent.getBoolean("modelFlag"),agent.getBoolean("effortFlag"))
                }
                refresh(h)
            } catch(e:CancellationException){h.client.close();h.ssh.disconnectNow();throw e}
            catch(e:Exception){h.client.close();h.ssh.disconnect();h.phase=ConnectionPhase.Disconnected;publish(h);postMessage(e.message?:"Could not connect this host.")}
            finally {key?.fill(0)}
        }
    }
    fun disconnect() {val h=current()?:return;h.connectJob?.cancel();closeTerminal();h.client.close();h.ssh.disconnectNow();h.phase=ConnectionPhase.Disconnected;_uiState.update {it.copy(selectedSessionName=null,selectedSessionId=null)};publish(h)}
    private suspend fun refresh(h:HostState) {
        val sessions=h.client.request("sessions") as JSONArray
        h.sessions=(0 until sessions.length()).map {i->val s=sessions.getJSONObject(i);TmuxSession(s.getString("name"),s.getInt("windows"),s.getInt("attached"),0,s.optString("agent","shell"),s.getString("id"),s.getString("stamp"))}
        val repos=h.client.request("repositories") as JSONObject
        val list=repos.getJSONArray("repos");h.repositories=(0 until list.length()).map {list.getJSONObject(it).getString("path")}
        publish(h)
    }
    fun refreshSessions() {val h=current()?:return;if(h.refreshing)return;viewModelScope.launch {h.refreshing=true;publish(h);try {refresh(h)}catch(e:Exception){postMessage(e.message?:"Refresh failed.")}finally {h.refreshing=false;publish(h)}}}
    fun reloadProfile() {val h=current()?:return;viewModelScope.launch {try {val shared=SharedProfile(h.client.request("profile_load") as JSONObject);shared.rebindOwner(h.profile.id,h.fingerprint);h.shared=shared;h.conflict=false;publish(h);refresh(h)}catch(e:Exception){postMessage(e.message?:"Reload failed.")}}}
    fun selectSession(name:String) {val h=current()?:return;val s=h.sessions.firstOrNull {it.name==name}?:return;openSession(h,s.id,s.stamp,s.name)}
    fun selectPane(row:TerminalRow) {
        val h=hosts.values.firstOrNull {it.fingerprint==row.hostKey && it.phase==ConnectionPhase.Connected}
        if(h==null){postMessage("Connect the terminal's host before opening it. Its saved target has been preserved.");return}
        openSession(h,row.sessionId,row.stamp,row.name)
    }
    private fun openSession(h:HostState,id:String,stamp:String,name:String) {
        closeTerminal()
        _uiState.update {it.copy(selectedSessionName=name,selectedSessionId=id,selectedStamp=stamp,selectedTargetHost=h.profile.id,selectedTargetLabel=h.profile.name.ifBlank {h.profile.host},tmuxSettings=null,windows=emptyList(),terminalEpoch=attachmentGeneration)}
        if(!foreground)return
        val generation=attachmentGeneration
        attachment=viewModelScope.launch {
            var stream:TerminalStream?=null
            try {
                h.client.request("session_context",JSONObject().put("id",id).put("stamp",stamp),15_000)
                if(generation!=attachmentGeneration)return@launch
                stream=TerminalStream(h.ssh,viewModelScope);stream.attach(h.profile.socket,id,stamp,80,24)
                if(generation!=attachmentGeneration){stream.close();return@launch}
                _uiState.update {it.copy(terminal=stream)}
            }catch(e:CancellationException){stream?.close();throw e}
            catch(e:Exception){stream?.close();if(generation==attachmentGeneration)postMessage(e.message?:"Terminal connection failed. Refresh to retry.")}
        }
    }
    private fun closeTerminal() {attachmentGeneration++;attachment?.cancel();attachment=null;_uiState.value.terminal?.close();_uiState.update {it.copy(terminal=null,terminalEpoch=attachmentGeneration,tmuxSettings=null,windows=emptyList(),draftReview=null)}}
    fun refreshTerminal() {val s=_uiState.value;val h=hosts[s.selectedTargetHost]?:return;openSession(h,s.selectedSessionId?:return,s.selectedStamp?:return,s.selectedSessionName.orEmpty())}
    suspend fun prepareInputTarget():String {
        val state=_uiState.value
        val h=hosts[state.selectedTargetHost]?:error("Terminal host is disconnected.")
        require(state.terminal?.connected==true){"Reconnect this terminal before preparing input."}
        val args=JSONObject().put("id",state.selectedSessionId).put("stamp",state.selectedStamp)
        val context=h.client.request("session_context",args,15_000) as String
        val pid=context.split(':').getOrNull(3)?.toIntOrNull()?:error("Terminal process is unavailable.")
        val process=h.client.request("process_context",JSONObject().put("pid",pid),15_000).toString()
        return JSONObject().put("host",h.profile.id).put("fingerprint",h.fingerprint).put("session",state.selectedSessionId).put("stamp",state.selectedStamp).put("context",context).put("process",process).toString()
    }
    suspend fun validateInputTarget(token:String):Boolean = try {
        val old=JSONObject(token);val state=_uiState.value
        if(old.getString("host")!=state.selectedTargetHost || old.getString("session")!=state.selectedSessionId || old.getString("stamp")!=state.selectedStamp)false
        else {
            val fresh=JSONObject(prepareInputTarget())
            listOf("fingerprint","context","process").all {fresh.getString(it)==old.getString(it)}
        }
    }catch(e:Exception){false}
    fun readTmuxSettings() {val state=_uiState.value;val h=hosts[state.selectedTargetHost]?:return
        viewModelScope.launch {try {
            val result=h.client.request("tmux_settings_read",JSONObject().put("session",state.selectedSessionId).put("stamp",state.selectedStamp)) as JSONObject
            if(_uiState.value.terminalEpoch==state.terminalEpoch)_uiState.update {it.copy(tmuxSettings=result)}
        }catch(e:Exception){postMessage(e.message?:"Could not read settings.")}}
    }
    fun applyTmuxSetting(name:String,value:String) {val state=_uiState.value;val h=hosts[state.selectedTargetHost]?:return;val settings=state.tmuxSettings?:return
        viewModelScope.launch {try {
            val args=JSONObject().put("session",state.selectedSessionId).put("stamp",state.selectedStamp).put("window",settings.optString("window"))
                .put("revision",settings.getString("revision")).put("name",name).put("value",value)
            val result=h.client.request("tmux_settings_apply",args) as JSONObject
            if(_uiState.value.terminalEpoch==state.terminalEpoch)_uiState.update {it.copy(tmuxSettings=result)}
        }catch(e:Exception){postMessage(e.message?:"Settings changed. Refresh before applying.")}}
    }
    fun readWindows() {val state=_uiState.value;val h=hosts[state.selectedTargetHost]?:return
        viewModelScope.launch {try {
            val result=h.client.request("tmux_settings_read",JSONObject().put("session",state.selectedSessionId).put("stamp",state.selectedStamp)) as JSONObject
            val rows=result.getJSONArray("windows")
            if(_uiState.value.terminalEpoch==state.terminalEpoch)_uiState.update {it.copy(windows=(0 until rows.length()).map {i->val w=rows.getJSONObject(i);TmuxWindow(w.getString("id"),w.getString("name"),w.getBoolean("active"))})}
        }catch(e:Exception){postMessage(e.message?:"Could not read tmux windows.")}}
    }
    fun selectWindow(id:String) {val state=_uiState.value;val h=hosts[state.selectedTargetHost]?:return
        viewModelScope.launch {try {
            h.client.request("tmux_window_select",JSONObject().put("session",state.selectedSessionId).put("stamp",state.selectedStamp).put("window",id))
            if(_uiState.value.terminalEpoch==state.terminalEpoch)dismissWindows()
        }catch(e:Exception){postMessage(e.message?:"Window changed; refresh before choosing.")}}
    }
    fun dismissWindows(){_uiState.update {it.copy(windows=emptyList())}}
    fun reviewDraft() {val h=current()?:return
        viewModelScope.launch {try {
            val base=store.draftBase(h.profile.id)?:error("This draft has no recovery base. It is kept on this phone; reload before making a fresh edit.")
            val draft=store.draft(h.profile.id)?:error("No saved draft.")
            val fresh=SharedProfile(h.client.request("profile_load") as JSONObject);fresh.rebindOwner(h.profile.id,h.fingerprint)
            val merged=ProfileMerge.reapply(base,draft,fresh.document)
            h.shared=fresh;publish(h)
            if(current()===h)_uiState.update {it.copy(draftReview=merged)}
        }catch(e:Exception){postMessage(e.message?:"Draft cannot be safely reapplied. It remains saved.")}}
    }
    fun discardDraft(){val h=current()?:return;store.clearDraft(h.profile.id);h.conflict=false;dismissDraftReview();publish(h)}
    fun dismissDraftReview(){_uiState.update {it.copy(draftReview=null)}}
    fun applyDraftReview() {val h=current()?:return;val review=_uiState.value.draftReview?:return
        viewModelScope.launch {try {save(h,SharedProfile(review),recovery=true);dismissDraftReview()}catch(e:Exception){postMessage(e.message?:"Host changed again. Draft remains saved; review again.")}}
    }
    fun dismissTmuxSettings(){_uiState.update {it.copy(tmuxSettings=null)}}
    fun leaveTerminal() {closeTerminal();_uiState.update {it.copy(selectedSessionName=null,selectedSessionId=null,selectedTargetHost=null)}}
    fun sendInput(input:String) {if(!_uiState.value.terminal.let {it?.send(input.toByteArray())==true})postMessage("Input was not sent. Reconnect the terminal; input is never replayed.")}
    private suspend fun save(h:HostState, draft:SharedProfile,recovery:Boolean=false) = h.edits.withLock {
        require(recovery || store.draft(h.profile.id)==null){"Review the saved draft before making another edit. Your draft is kept."}
        store.retainDraft(h.profile.id,draft.document,h.shared?.document)
        try {
            val result=h.client.request("profile_save",JSONObject().put("profile",draft.document).put("revision",draft.revision)) as JSONObject
            h.shared=SharedProfile(result);h.conflict=false;store.clearDraft(h.profile.id);publish(h)
        }catch(e:Exception){if(e.message?.contains("CONFLICT") == true){h.conflict=true;publish(h)};throw e}
    }
    fun editWorkspace(id:String,name:String?=null,remove:Boolean=false,move:Int=0,background:String?=null) {val h=current()?:return;viewModelScope.launch {try {val draft=SharedProfile(requireNotNull(h.shared).document);draft.editWorkspace(id,name,remove,move,background);save(h,draft)}catch(e:Exception){postMessage(e.message?:"Workspace edit failed. Draft retained.")}}}
    fun adoptSession(name:String) {val h=current()?:return;val s=h.sessions.firstOrNull {it.name==name}?:return;viewModelScope.launch {try {val draft=SharedProfile(requireNotNull(h.shared).document);draft.addSession(h.profile.id,h.fingerprint,JSONObject().put("id",s.id).put("name",s.name).put("stamp",s.stamp).put("agent",s.currentCommand),"");save(h,draft)}catch(e:Exception){postMessage(e.message?:"Could not save workspace. Your draft is retained.")}}}
    fun createSession(launch:SessionLaunch) {
        val h=current()?:return;if(h.creating)return
        if(store.draft(h.profile.id)!=null){postMessage("Review the saved draft before starting more terminals.");return}
        val navigationGeneration=attachmentGeneration
        viewModelScope.launch {
            h.creating=true;publish(h)
            val request=launch.arguments().apply {remove("operation")}.put("count",launch.count).put("workspaceId",launch.workspaceId).put("workspaceName",launch.workspaceName).toString()
            val operation=store.launchOperation(h.profile.id,request)
            val created=mutableListOf<JSONObject>()
            var draft:SharedProfile?=null
            try {
                require(launch.count in 1..8){"Choose between 1 and 8 terminals."}
                draft=SharedProfile(requireNotNull(h.shared).document)
                var workspace=launch.workspaceId
                for(i in 0 until launch.count) {
                    val args=launch.arguments().put("operation","$operation-$i").put("name",if(launch.count==1)launch.name else "${launch.name}-${i+1}")
                    val session=h.client.request("create",args) as JSONObject
                    created.add(session)
                    workspace=draft.addSession(h.profile.id,h.fingerprint,session.put("agent",launch.agent).put("model",launch.model).put("effort",launch.effort).put("identity",launch.identity).put("operation","$operation-$i"),launch.path,workspace,launch.workspaceName)
                }
                save(h,draft);store.completeLaunch(h.profile.id,request);refresh(h)
                val first=created.first()
                if(attachmentGeneration==navigationGeneration && current()===h && !_uiState.value.showHosts)openSession(h,first.getString("id"),first.getString("stamp"),first.getString("name"))
                else postMessage("Created ${created.size} terminals on ${h.profile.name.ifBlank {h.profile.host}}.")
            }catch(e:Exception){
                if(created.isNotEmpty() && draft!=null)store.retainDraft(h.profile.id,draft.document,h.shared?.document)
                postMessage((e.message?:"Session creation failed.")+" Refresh before retrying; any created sessions and unsaved draft are retained.")
                runCatching {refresh(h)}
            } finally {h.creating=false;publish(h)}
        }
    }
    fun renameSession(id:String,stamp:String,name:String) {val h=current()?:return;viewModelScope.launch {try {h.client.request("rename",JSONObject().put("id",id).put("stamp",stamp).put("name",name));refresh(h)}catch(e:Exception){postMessage(e.message?:"Could not rename this session.")}}}
    fun endSession(id:String,stamp:String,name:String) {val h=current()?:return;viewModelScope.launch {try {h.ending=name;publish(h);h.client.request("destroy",JSONObject().put("id",id).put("stamp",stamp));refresh(h)}catch(e:Exception){postMessage(e.message?:"Could not end this session.")}finally {h.ending=null;publish(h)}}}
    fun answerHostKeyChallenge(hostId:String,id:Long,trust:Boolean){
        if(challenges[hostId]?.id!=id)return
        hosts[hostId]?.prompts?.answer(id,trust);challenges.remove(hostId);publishChallenge()
    }
    fun setForeground(value:Boolean){foreground=value;if(!value)closeTerminal()else if(_uiState.value.selectedSessionId!=null)refreshTerminal()}
    fun consumeMessage(id:Long){_uiState.update {if(it.message?.id==id)it.copy(message=null)else it}}
    private fun postMessage(text:String){_uiState.update {it.copy(message=UserMessage(ids.getAndIncrement(),text))}}
    private suspend fun readPrivateKey(value:String):ByteArray=withContext(Dispatchers.IO){getApplication<Application>().contentResolver.openInputStream(value.toUri())?.use {val buffer=ByteArray(1_048_577);try {var total=0;while(total<buffer.size){val count=it.read(buffer,total,buffer.size-total);if(count<0)break;total+=count};require(total<=1_048_576){"Private key exceeds 1 MiB."};buffer.copyOf(total)}finally{buffer.fill(0)}}?:error("Could not open this private key.")}
    override fun onCleared(){closeTerminal();hosts.values.forEach {it.connectJob?.cancel();it.client.close();it.ssh.disconnectNow();it.promptJob.cancel()};super.onCleared()}
}

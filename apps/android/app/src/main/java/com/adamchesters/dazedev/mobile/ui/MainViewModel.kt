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
import com.adamchesters.dazedev.mobile.releases.*
import com.adamchesters.dazedev.mobile.BuildConfig
import com.adamchesters.dazedev.mobile.ssh.HostKeyNotice
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
data class PendingTerminalConnection(val row:TerminalRow,val ownerId:String,val folderId:String?,val targetProfileId:String)
data class StaleSession(val hostId:String,val name:String,val replacement:TmuxSession?)
data class HostKeyChange(val hostId:String,val label:String,val notice:HostKeyNotice)
data class HostKeyEntry(val hostId:String,val label:String,val type:String,val fingerprint:String)
data class BrowserRequest(val id:Long,val host:String,val url:String)
data class AuthNotice(val hostId:String,val host:String,val text:String,val url:String?)
enum class BrowserAnswer { Once, Connection, Deny }
data class HostSummary(val profile: ConnectionProfile, val phase: ConnectionPhase, val workspaceCount: Int, val fingerprint: String = "", val folders:List<WorkspaceFolder> = emptyList())
data class DazeDevMobileUiState(
    val localGroups:List<WorkspaceFolder> = emptyList(),
    val localGroupId:String? = null,
    val groupConnectPrompt:Boolean = false,
    val groupConnectionHost:String? = null,
    val selectedPaneId:String? = null,
    val releaseHistory:List<ReleaseEntry> = emptyList(),
    val releaseHistoryStatus:String = "",
    val mobileUpdate:MobileUpdate? = null,
    val checkingUpdates:Boolean = false,
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
    val pendingTerminalConnection:PendingTerminalConnection? = null,
    val sessionsLoading:Boolean = false,
    val terminalError:String? = null,
    val staleSession:StaleSession? = null,
    val linkError:String? = null,
    val hostKeyChange:HostKeyChange? = null,
    val hostKeys:List<HostKeyEntry>? = null,
    val browserRequest:BrowserRequest? = null,
    val authNotice:AuthNotice? = null,
    val secureScreen:Boolean = false,
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val store=ProfileStore(application)
    private val localGroups=store.localWorkspaces()?.let(::SharedProfile)?:SharedProfile.empty()
    private var groupConnectQueue=listOf<String>()
    private val ids=AtomicLong(1)
    private val _uiState=MutableStateFlow(DazeDevMobileUiState(profile=store.load(),secureScreen=store.secureScreen))
    val uiState: StateFlow<DazeDevMobileUiState> = _uiState.asStateFlow()
    private var foreground=true
    private var backgroundDetach: Job?=null
    private var attachment: Job?=null
    private val terminalSize=MutableStateFlow<Pair<Int,Int>?>(null)
    private var resumeAfterConnect:Pair<String,Triple<String,String,String>>?=null
    private var browserAnswer:CompletableDeferred<BrowserAnswer>?=null
    private var attachmentGeneration=0L
    private val hosts=linkedMapOf<String, HostState>()
    private val challenges=linkedMapOf<String,HostKeyChallenge>()
    private fun publishChallenge(){val first=challenges.entries.firstOrNull();_uiState.update {it.copy(hostKeyChallenge=first?.value,challengeHostId=first?.key.orEmpty(),challengeHost=hosts[first?.key]?.profile?.host.orEmpty())}}
    private fun knownHosts(id:String)=File(getApplication<Application>().filesDir,"ssh/$id/known_hosts")
    private inner class HostState(var profile: ConnectionProfile) {
        val prompts=HostKeyPromptCoordinator()
        val ssh=SshConnection(knownHosts(profile.id),prompts) {text->viewModelScope.launch(Dispatchers.Main){showAuthNotice(this@HostState,text)}}
        val client=HostClient(ssh,viewModelScope,{url->openHostUrl(this,url)}) {reason->viewModelScope.launch(Dispatchers.Main){linkLost(this@HostState,reason)}}
        val label get()=profile.name.ifBlank {profile.host}
        var phase=ConnectionPhase.Disconnected
        var sessionsLoaded=false
        var linkError:String?=null
        var allowBrowser=false
        var background:Job?=null
        var shared: SharedProfile?=null
        var fingerprint=profile.fingerprint
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
    init {
        store.loadAll().forEach {hosts[it.id]=HostState(it)};publishHosts()
        publishLocalGroups()
        val bundled=application.assets.open("changelog.json").bufferedReader().use {it.readText()}
        _uiState.update {it.copy(releaseHistory=mergeHistory(store.releaseHistory(),mergeHistory(bundled)))}
        if(System.currentTimeMillis()-store.lastUpdateCheck()>24*60*60*1000L)checkUpdates(false)
    }
    fun checkUpdates(manual:Boolean=true) {
        if(_uiState.value.checkingUpdates)return
        _uiState.update {it.copy(checkingUpdates=true)}
        viewModelScope.launch {
            try {
                store.checkedUpdates()
                val update=withContext(Dispatchers.IO){PublicReleaseCatalog.updates(BuildConfig.VERSION_NAME)}
                if(update!=null && (manual || update.version!=store.ignoredUpdate()))_uiState.update {it.copy(mobileUpdate=update)}
                else if(manual)postMessage("No newer public Android release is available. Development APKs can be installed offline.")
            } catch(e:Exception) {if(e is CancellationException)throw e;if(manual)postMessage(e.message?:"Public releases are unavailable.")}
            finally {_uiState.update {it.copy(checkingUpdates=false)}}
        }
    }
    fun dismissUpdate() {_uiState.value.mobileUpdate?.let {store.ignoreUpdate(it.version)};_uiState.update {it.copy(mobileUpdate=null)}}
    fun refreshReleaseHistory() {
        if(_uiState.value.releaseHistoryStatus=="Checking public release history…")return
        _uiState.update {it.copy(releaseHistoryStatus="Checking public release history…")}
        viewModelScope.launch {
            try {
                val json=withContext(Dispatchers.IO){PublicReleaseCatalog.history()}
                val history=mergeHistory(json,_uiState.value.releaseHistory)
                store.saveReleaseHistory(historyJson(history))
                _uiState.update {it.copy(releaseHistory=history,releaseHistoryStatus="")}
            } catch(e:Exception) {if(e is CancellationException)throw e;_uiState.update {it.copy(releaseHistoryStatus="Showing saved release history. Public history is currently unavailable.")}}
        }
    }
    private fun current(): HostState? = hosts[_uiState.value.profile.id]
    private fun publishHosts() { _uiState.update {it.copy(hosts=hosts.values.map {h->HostSummary(h.profile,h.phase,h.shared?.folders()?.size?:0,h.fingerprint,h.shared?.folders().orEmpty())})} }
    private fun publish(h: HostState) {
        publishHosts()
        if (_uiState.value.profile.id != h.profile.id) return
        _uiState.update {it.copy(profile=h.profile,connectionPhase=h.phase,folders=h.shared?.folders().orEmpty(),sessions=h.sessions,repositories=h.repositories,agents=h.agents,conflict=h.conflict,recoveryDraft=store.draft(h.profile.id)!=null,isRefreshing=h.refreshing,isCreating=h.creating,endingSessionName=h.ending,
            sessionsLoading=h.phase==ConnectionPhase.Connected && !h.sessionsLoaded,linkError=if(h.phase==ConnectionPhase.Connected)null else h.linkError)}
    }
    private fun publishLocalGroups(){_uiState.update {it.copy(localGroups=localGroups.folders())}}
    private fun saveLocalGroups(){store.saveLocalWorkspaces(localGroups.document);publishLocalGroups()}
    fun createLocalGroup(name:String){val id=localGroups.createLocalWorkspace(name.trim());saveLocalGroups();selectLocalGroup(id)}
    fun selectLocalGroup(id:String) {
        closeTerminal();groupConnectQueue=emptyList()
        _uiState.update {it.copy(showHosts=false,localGroupId=id,selectedSessionName=null,selectedSessionId=null,selectedPaneId=null,selectedFolder=null,groupConnectionHost=null,pendingTerminalConnection=null)}
        val needs=_uiState.value.groupHosts()
        _uiState.update {it.copy(groupConnectPrompt=needs.isNotEmpty())}
    }
    fun dismissGroupConnect(){_uiState.update {it.copy(groupConnectPrompt=false)}}
    fun connectGroupHosts() {
        groupConnectQueue=_uiState.value.groupHosts().mapNotNull {it.host?.profile?.id}.distinct()
        _uiState.update {it.copy(groupConnectPrompt=false)}
        nextGroupConnection()
    }
    private fun nextGroupConnection():Unit {
        groupConnectQueue=groupConnectQueue.filter {hosts[it]?.phase!=ConnectionPhase.Connected}
        val id=groupConnectQueue.firstOrNull()
        if(id==null){_uiState.update {it.copy(groupConnectionHost=null)};return}
        groupConnectQueue=groupConnectQueue.drop(1)
        val host=hosts[id]?:return nextGroupConnection()
        _uiState.update {it.copy(groupConnectionHost=id,profile=host.profile,connectionPhase=host.phase)}
        if(host.profile.authMethod==AuthMethod.TailscaleSsh)connect(host.profile,"")
    }
    fun cloneToLocalGroup(sourceHost:String,folder:String,pane:String) {
        try {localGroups.clonePane(_uiState.value.localGroupId?:return,hosts[sourceHost]?.shared?:error("Connect the source host first."),folder,pane);saveLocalGroups()}
        catch(e:Exception){postMessage(e.message?:"Could not add this terminal view.")}
    }
    fun editLocalGroup(name:String?=null,remove:Boolean=false) {
        localGroups.editWorkspace(_uiState.value.localGroupId?:return,name=name,remove=remove)
        saveLocalGroups();if(remove)showHosts()
    }
    fun groupTerminalTabs(panes:List<String>)=editTabs {profile,id->profile.groupTabs(id,panes)}
    fun ungroupTerminalTab(pane:String)=editTabs {profile,id->profile.ungroupTab(id,pane)}
    fun removeLocalTerminalView(pane:String) {
        try {localGroups.removeLocalView(_uiState.value.localGroupId?:return,pane);saveLocalGroups()}
        catch(e:Exception){postMessage(e.message?:"Could not remove this terminal view.")}
    }
    private fun editTabs(edit:(SharedProfile,String)->Unit) {
        val local=_uiState.value.localGroupId
        if(local!=null){try {edit(localGroups,local);saveLocalGroups()}catch(e:Exception){postMessage(e.message?:"Could not group terminals.")};return}
        val h=current()?:return;val folder=_uiState.value.selectedFolder?:return
        viewModelScope.launch {try {val draft=SharedProfile(requireNotNull(h.shared).document);edit(draft,folder);save(h,draft)}catch(e:Exception){postMessage(e.message?:"Could not save terminal tabs. Draft retained.")}}
    }
    fun showHosts() {closeTerminal();groupConnectQueue=emptyList();_uiState.update {it.copy(showHosts=true,localGroupId=null,groupConnectPrompt=false,groupConnectionHost=null,selectedFolder=null,selectedSessionName=null,selectedSessionId=null,pendingTerminalConnection=null)}}
    fun newHost() {closeTerminal();_uiState.update {it.copy(profile=ConnectionProfile(),localGroupId=null,showHosts=false,connectionPhase=ConnectionPhase.Disconnected,selectedFolder=null,selectedSessionName=null,pendingTerminalConnection=null)}}
    fun selectHost(id: String) {
        val h=hosts[id]?:return;closeTerminal()
        _uiState.update {it.copy(profile=h.profile,localGroupId=null,showHosts=false,selectedFolder=null,selectedSessionName=null,selectedSessionId=null,pendingTerminalConnection=null)};publish(h)
    }
    fun selectFolder(id: String?) {_uiState.update {it.copy(selectedFolder=id)}}
    fun setPrivateKey(uri: Uri, displayName: String) {_uiState.update {it.copy(profile=it.profile.copy(privateKeyUri=uri.toString(),privateKeyName=displayName))}}
    fun connect(profile: ConnectionProfile, secret: String) {
        val normalized=profile.copy(host=profile.host.trim(),username=profile.username.trim(),workingDirectory=profile.workingDirectory.trim(),socket=profile.socket.trim())
        normalized.validationError(secret)?.let {postMessage(it);return}
        val h=hosts.getOrPut(normalized.id){HostState(normalized)}
        if(h.connectJob?.isCompleted==false){postMessage("Wait for this host connection to finish or cancel.");return}
        h.profile=normalized;h.phase=ConnectionPhase.Connecting;h.linkError=null;publish(h)
        _uiState.update {it.copy(hostKeyChange=null)}
        h.connectJob=viewModelScope.launch {
            var key:ByteArray?=null
            try {
                if(normalized.authMethod==AuthMethod.PrivateKey)key=readPrivateKey(requireNotNull(normalized.privateKeyUri))
                h.ssh.connect(normalized,secret,key)
                val source=withContext(Dispatchers.IO){getApplication<Application>().assets.open("host.py").use {it.readBytes()}}
                val browserSource=withContext(Dispatchers.IO){getApplication<Application>().assets.open("browser.py").use {it.readBytes()}}
                val ready=h.client.start(source,normalized.socket,browserSource,h.label)
                h.fingerprint="${ready.getString("machine")}:${ready.getString("user")}:${normalized.socket}"
                val pending=_uiState.value.pendingTerminalConnection
                if(pending?.targetProfileId==normalized.id) require(pending.row.hostKey==h.fingerprint) {
                    "This address reached a different host, user or tmux socket. The saved terminal was not opened. Check the connection details."
                }
                h.profile=normalized.copy(fingerprint=h.fingerprint)
                val shared=SharedProfile(h.client.request("profile_load") as JSONObject)
                shared.rebindOwner(normalized.id,h.fingerprint,HostReference.fromProfile(h.profile));h.shared=shared
                h.sessionsLoaded=false;h.allowBrowser=false;h.sessions=emptyList()
                h.phase=ConnectionPhase.Connected;store.save(h.profile);publish(h)
                clearAuthNotice(h)
                // Sessions first: slow agent and repository scans load afterwards.
                loadSessions(h)
                if(_uiState.value.groupConnectionHost==normalized.id)nextGroupConnection()
                val selected=_uiState.value.pendingTerminalConnection
                val resume=resumeAfterConnect?.takeIf {it.first==normalized.id}?.second
                if(selected?.targetProfileId==normalized.id) {
                    _uiState.update {it.copy(pendingTerminalConnection=null)}
                    hosts[selected.ownerId]?.let {owner->
                        _uiState.update {it.copy(profile=owner.profile,selectedFolder=selected.folderId)}
                        publish(owner)
                    }
                    openSession(h,selected.row.sessionId,selected.row.stamp,selected.row.name)
                } else if(resume!=null) {
                    resumeAfterConnect=null
                    openSession(h,resume.first,resume.second,resume.third)
                }
                h.background?.cancel()
                h.background=viewModelScope.launch {loadCapabilities(h);loadRepositories(h)}
            } catch(e:HostKeyChangedException){
                h.client.close();h.ssh.disconnect();h.phase=ConnectionPhase.Disconnected;publish(h);clearAuthNotice(h)
                _uiState.update {it.copy(hostKeyChange=HostKeyChange(h.profile.id,h.label,e.notice))}
            } catch(e:TimeoutCancellationException){
                h.client.close();h.ssh.disconnect();h.phase=ConnectionPhase.Disconnected;publish(h);clearAuthNotice(h)
                postMessage("${h.label} did not answer in time. Check the host and network, then connect again.")
            } catch(e:CancellationException){h.client.close();h.ssh.disconnectNow();clearAuthNotice(h);throw e}
            catch(e:Exception){h.client.close();h.ssh.disconnect();h.phase=ConnectionPhase.Disconnected;publish(h);clearAuthNotice(h);postMessage(e.message?:"Could not connect this host.")}
            finally {key?.fill(0)}
        }
    }
    private suspend fun loadSessions(h:HostState) {
        val sessions=h.client.request("sessions") as JSONArray
        h.sessions=(0 until sessions.length()).map {i->val s=sessions.getJSONObject(i);TmuxSession(s.getString("name"),s.getInt("windows"),s.getInt("attached"),0,s.optString("agent","shell"),s.getString("id"),s.getString("stamp"))}
        h.sessionsLoaded=true
        publish(h)
    }
    private suspend fun loadCapabilities(h:HostState) {
        try {
            val capabilities=h.client.request("capabilities") as JSONObject
            val advertised=capabilities.getJSONArray("agents")
            h.agents=(0 until advertised.length()).map {index->
                val agent=advertised.getJSONObject(index);val models=agent.getJSONArray("models")
                val efforts=(0 until models.length()).associate {i->val m=models.getJSONObject(i);val e=m.getJSONArray("efforts");m.getString("id") to (0 until e.length()).map {e.getString(it)}}
                AgentCapability(agent.getString("id"),agent.optString("version"),efforts.keys.toList(),efforts,agent.getBoolean("modelFlag"),agent.getBoolean("effortFlag"))
            }
            publish(h)
        } catch(e:CancellationException){throw e}
        catch(e:Exception){if(h.phase==ConnectionPhase.Connected)postMessage("Could not list agents on ${h.label}: ${e.message}")}
    }
    private suspend fun loadRepositories(h:HostState) {
        try {
            val repos=h.client.request("repositories") as JSONObject
            val list=repos.getJSONArray("repos");h.repositories=(0 until list.length()).map {list.getJSONObject(it).getString("path")}
            publish(h)
        } catch(e:CancellationException){throw e}
        catch(e:Exception){if(h.phase==ConnectionPhase.Connected)postMessage("Could not list repositories on ${h.label}: ${e.message}")}
    }
    fun disconnect() {val h=current()?:return;h.connectJob?.cancel();h.background?.cancel();closeTerminal();h.client.close();h.ssh.disconnectNow();h.phase=ConnectionPhase.Disconnected;h.linkError=null;_uiState.update {it.copy(selectedSessionName=null,selectedSessionId=null)};publish(h)}
    private fun linkLost(h:HostState,reason:String) {
        if(h.phase!=ConnectionPhase.Connected)return
        h.background?.cancel();h.client.close();h.ssh.disconnectNow()
        h.phase=ConnectionPhase.Disconnected;h.linkError="Lost connection to ${h.label}. $reason"
        if(_uiState.value.selectedTargetHost==h.profile.id){closeTerminal();_uiState.update {it.copy(terminalError=h.linkError)}}
        publish(h)
    }
    /** Profile and sessions; repositories refresh in the background. */
    private suspend fun refresh(h:HostState) {
        val shared=SharedProfile(h.client.request("profile_load") as JSONObject)
        shared.rebindOwner(h.profile.id,h.fingerprint,HostReference.fromProfile(h.profile));h.shared=shared
        loadSessions(h)
        viewModelScope.launch {loadRepositories(h)}
    }
    fun refreshSessions() {val h=current()?:return;if(h.refreshing)return;viewModelScope.launch {h.refreshing=true;publish(h);try {refresh(h)}catch(e:Exception){postMessage(e.message?:"Refresh failed.")}finally {h.refreshing=false;publish(h)}}}
    fun reloadProfile() {val h=current()?:return;viewModelScope.launch {try {val shared=SharedProfile(h.client.request("profile_load") as JSONObject);shared.rebindOwner(h.profile.id,h.fingerprint,HostReference.fromProfile(h.profile));h.shared=shared;h.conflict=false;publish(h);refresh(h)}catch(e:Exception){postMessage(e.message?:"Reload failed.")}}}
    fun selectSession(name:String) {val h=current()?:return;val s=h.sessions.firstOrNull {it.name==name}?:return;_uiState.update {it.copy(selectedPaneId=null)};openSession(h,s.id,s.stamp,s.name)}
    fun selectPane(row:TerminalRow) {
        if(row.hostKey.isBlank()) {postMessage("This terminal has no saved host identity. Open its host's session list and adopt it again.");return}
        _uiState.update {it.copy(selectedPaneId=row.paneId)}
        val state=_uiState.value
        val target=state.destination(row)
        if(target.connected) {
            val h=hosts[target.host?.profile?.id] ?: return
            openSession(h,row.sessionId,row.stamp,row.name)
            return
        }
        val profile=target.profile()
        val pending=PendingTerminalConnection(row,state.profile.id,state.selectedFolder,profile.id)
        closeTerminal()
        _uiState.update {it.copy(profile=profile,showHosts=false,connectionPhase=target.host?.phase ?: ConnectionPhase.Disconnected,
            selectedFolder=null,selectedSessionName=null,selectedSessionId=null,pendingTerminalConnection=pending)}
        if(profile.authMethod==AuthMethod.TailscaleSsh && profile.host.isNotBlank() && profile.username.isNotBlank()) connect(profile,"")
    }
    fun cancelConnection() {
        if(_uiState.value.groupConnectionHost!=null) {
            hosts[_uiState.value.groupConnectionHost]?.let {h->h.connectJob?.cancel();h.client.close();h.ssh.disconnectNow();h.phase=ConnectionPhase.Disconnected;publish(h)}
            groupConnectQueue=emptyList();_uiState.update {it.copy(groupConnectionHost=null)};return
        }
        val pending=_uiState.value.pendingTerminalConnection
        if(pending==null) {disconnect();showHosts();return}
        hosts[pending.targetProfileId]?.let {h->h.connectJob?.cancel();h.client.close();h.ssh.disconnectNow();h.phase=ConnectionPhase.Disconnected}
        _uiState.update {it.copy(pendingTerminalConnection=null)}
        val owner=hosts[pending.ownerId]
        if(owner==null)showHosts() else {
            _uiState.update {it.copy(profile=owner.profile,showHosts=false,selectedFolder=pending.folderId)}
            publish(owner)
        }
    }
    private fun openSession(h:HostState,id:String,stamp:String,name:String) {
        closeTerminal()
        _uiState.update {it.copy(selectedSessionName=name,selectedSessionId=id,selectedStamp=stamp,selectedTargetHost=h.profile.id,selectedTargetLabel=h.label,tmuxSettings=null,windows=emptyList(),terminalEpoch=attachmentGeneration,terminalError=null,staleSession=null)}
        if(!foreground)return
        val generation=attachmentGeneration
        attachment=viewModelScope.launch {
            var stream:TerminalStream?=null
            try {
                h.client.request("session_context",JSONObject().put("id",id).put("stamp",stamp),15_000)
                if(generation!=attachmentGeneration)return@launch
                val browser=try {h.client.request("browser_prepare") as JSONObject}
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){postMessage("Browser forwarding is unavailable. You can still use this terminal and tap its web links.");null}
                // Attach at the measured size so other clients do not reflow several times.
                val size=withTimeoutOrNull(10_000){terminalSize.filterNotNull().first();delay(200);terminalSize.value} ?: terminalSize.value ?: (80 to 24)
                if(generation!=attachmentGeneration)return@launch
                stream=TerminalStream(h.ssh,viewModelScope);stream.attach(h.profile.socket,id,stamp,size.first,size.second,browser)
                if(generation!=attachmentGeneration){stream.close();return@launch}
                terminalSize.value?.let {stream.resize(it.first,it.second)}
                _uiState.update {it.copy(terminal=stream)}
            }catch(e:TimeoutCancellationException){stream?.close();if(generation==attachmentGeneration)_uiState.update {it.copy(terminalError="The terminal did not connect in time. Try again.")}}
            catch(e:CancellationException){stream?.close();throw e}
            catch(e:Exception){stream?.close();if(generation==attachmentGeneration)openFailed(h,id,stamp,name,e)}
        }
    }
    private suspend fun openFailed(h:HostState,id:String,stamp:String,name:String,e:Exception) {
        val message=e.message.orEmpty()
        if(!(message.contains("Session changed") || message.contains("no longer exists") || message.contains("ended or restarted"))) {
            _uiState.update {it.copy(terminalError=message.ifBlank {"Terminal connection failed."})}
            return
        }
        runCatching {loadSessions(h)}
        val replacement=h.sessions.firstOrNull {it.name==name && (it.id!=id || it.stamp!=stamp)}
        _uiState.update {it.copy(terminalError="Session ended or restarted.",staleSession=StaleSession(h.profile.id,name,replacement))}
    }
    /** Opens the running session with the saved name and points the saved terminal at it. */
    fun adoptStaleSession() {
        val stale=_uiState.value.staleSession?:return
        val h=hosts[stale.hostId]?:return
        val session=stale.replacement?:return
        _uiState.update {it.copy(staleSession=null)}
        _uiState.value.selectedPaneId?.let {pane->
            if(_uiState.value.localGroupId!=null) {if(localGroups.rebindPaneSession(pane,session.id,session.stamp))saveLocalGroups()}
            else current()?.let {owner->
                val shared=owner.shared
                if(shared!=null) viewModelScope.launch {
                    try {val draft=SharedProfile(shared.document);if(draft.rebindPaneSession(pane,session.id,session.stamp))save(owner,draft)}
                    catch(e:Exception){postMessage("The terminal opened, but its saved workspace entry was not updated. ${e.message.orEmpty()}")}
                }
            }
        }
        openSession(h,session.id,session.stamp,session.name)
    }
    fun dismissStaleSession(){_uiState.update {it.copy(staleSession=null)}}
    /** Called by the terminal view with its fitted size for the current attachment. */
    fun terminalMeasured(epoch:Long,cols:Int,rows:Int) {if(epoch==_uiState.value.terminalEpoch)terminalSize.value=cols.coerceIn(2,500) to rows.coerceIn(2,300)}
    private fun closeTerminal() {attachmentGeneration++;attachment?.cancel();attachment=null;terminalSize.value=null;_uiState.value.terminal?.close();_uiState.update {it.copy(terminal=null,terminalEpoch=attachmentGeneration,tmuxSettings=null,windows=emptyList(),draftReview=null)}}
    fun refreshTerminal() {val s=_uiState.value;val h=hosts[s.selectedTargetHost]?:return;openSession(h,s.selectedSessionId?:return,s.selectedStamp?:return,s.selectedSessionName.orEmpty())}
    /** Reattaches the terminal, reconnecting its host first when the link is gone. */
    fun reconnectTerminal() {
        val s=_uiState.value;val h=hosts[s.selectedTargetHost]?:return
        if(h.phase==ConnectionPhase.Connected && h.client.isOpen){refreshTerminal();return}
        if(h.phase==ConnectionPhase.Connecting)return
        val target=Triple(s.selectedSessionId?:return,s.selectedStamp?:return,s.selectedSessionName.orEmpty())
        resumeAfterConnect=h.profile.id to target
        if(h.profile.authMethod==AuthMethod.TailscaleSsh){_uiState.update {it.copy(terminalError=null)};connect(h.profile,"");return}
        // Password and key hosts need the secret again; the session reopens after connecting.
        closeTerminal()
        _uiState.update {it.copy(profile=h.profile,showHosts=false,localGroupId=null,selectedFolder=null,selectedSessionName=null,selectedSessionId=null,selectedTargetHost=null,terminalError=null)}
        publish(h)
    }
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
            val fresh=SharedProfile(h.client.request("profile_load") as JSONObject);fresh.rebindOwner(h.profile.id,h.fingerprint,HostReference.fromProfile(h.profile))
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
        draft.rebindOwner(h.profile.id,h.fingerprint,HostReference.fromProfile(h.profile))
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
    /** Keeps the terminal through rotation and short app switches; detaches after a grace period. */
    fun setForeground(value:Boolean){
        foreground=value
        backgroundDetach?.cancel();backgroundDetach=null
        if(!value){backgroundDetach=viewModelScope.launch {delay(BACKGROUND_GRACE_MS);closeTerminal()};return}
        val s=_uiState.value
        if(s.selectedSessionId!=null && s.staleSession==null && attachment?.isActive!=true && s.terminal?.connected!=true && hosts[s.selectedTargetHost]?.phase==ConnectionPhase.Connected)refreshTerminal()
    }
    private fun showAuthNotice(h:HostState,text:String) {
        val url=Regex("https://[^\\s]+").find(text)?.value?.trimEnd('.',',',')')
        _uiState.update {it.copy(authNotice=AuthNotice(h.profile.id,h.label,text,url))}
    }
    private fun clearAuthNotice(h:HostState){_uiState.update {if(it.authNotice?.hostId==h.profile.id)it.copy(authNotice=null)else it}}
    fun dismissAuthNotice(){_uiState.update {it.copy(authNotice=null)}}
    /** Host-initiated links ask first, unless allowed for this connection. */
    private suspend fun openHostUrl(h:HostState,url:String):String? {
        if(!foreground)return "Return to DazeDev before opening a browser link."
        runCatching {BrowserUrl.validate(url)}.onFailure {return it.message}
        if(!h.allowBrowser) {
            if(browserAnswer!=null)return "Another link is waiting for approval on the phone."
            val answer=CompletableDeferred<BrowserAnswer>();browserAnswer=answer
            _uiState.update {it.copy(browserRequest=BrowserRequest(ids.getAndIncrement(),h.label,url))}
            val result=try {withTimeoutOrNull(120_000){answer.await()}} finally {browserAnswer=null;_uiState.update {it.copy(browserRequest=null)}}
            when(result) {
                BrowserAnswer.Connection->h.allowBrowser=true
                BrowserAnswer.Once->Unit
                else->return "The phone user did not open this link."
            }
        }
        return BrowserLauncher.open(getApplication<Application>(),url).also {error->if(error!=null)postMessage(error)}
    }
    fun answerBrowserRequest(answer:BrowserAnswer){browserAnswer?.complete(answer)}
    fun openHostKeys() {
        viewModelScope.launch {
            val list=withContext(Dispatchers.IO){hosts.values.flatMap {h->KnownHostKeys.list(knownHosts(h.profile.id)).map {HostKeyEntry(h.profile.id,h.label,it.type,it.fingerprint)}}}
            _uiState.update {it.copy(hostKeys=list,hostKeyChange=null)}
        }
    }
    /** Blocks screenshots and the Recents preview when on. */
    fun setSecureScreen(value:Boolean){store.secureScreen=value;_uiState.update {it.copy(secureScreen=value)}}
    fun closeHostKeys(){_uiState.update {it.copy(hostKeys=null)}}
    fun dismissHostKeyChange(){_uiState.update {it.copy(hostKeyChange=null)}}
    fun forgetHostKey(entry:HostKeyEntry) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO){KnownHostKeys.forget(knownHosts(entry.hostId),entry.fingerprint)}
                postMessage("Forgot the saved key for ${entry.label}. The next connection shows the new fingerprint to check.")
            } catch(e:Exception){postMessage(e.message?:"Could not forget this key.")}
            openHostKeys()
        }
    }
    fun consumeMessage(id:Long){_uiState.update {if(it.message?.id==id)it.copy(message=null)else it}}
    private fun postMessage(text:String){_uiState.update {it.copy(message=UserMessage(ids.getAndIncrement(),text))}}
    private suspend fun readPrivateKey(value:String):ByteArray=withContext(Dispatchers.IO){getApplication<Application>().contentResolver.openInputStream(value.toUri())?.use {val buffer=ByteArray(1_048_577);try {var total=0;while(total<buffer.size){val count=it.read(buffer,total,buffer.size-total);if(count<0)break;total+=count};require(total<=1_048_576){"Private key exceeds 1 MiB."};buffer.copyOf(total)}finally{buffer.fill(0)}}?:error("Could not open this private key.")}
    override fun onCleared(){closeTerminal();hosts.values.forEach {it.connectJob?.cancel();it.client.close();it.ssh.disconnectNow();it.promptJob.cancel()};super.onCleared()}
    private companion object {const val BACKGROUND_GRACE_MS=120_000L}
}

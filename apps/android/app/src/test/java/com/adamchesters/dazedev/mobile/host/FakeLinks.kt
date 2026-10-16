package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.ConnectionProfile
import com.adamchesters.dazedev.mobile.data.HostStore
import com.adamchesters.dazedev.mobile.ssh.ExecResult
import com.adamchesters.dazedev.mobile.ssh.HostKeyPromptCoordinator
import com.adamchesters.dazedev.mobile.ssh.SshLink
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.json.JSONArray
import org.json.JSONObject

class FakeSsh : SshLink {
    var connects = 0
    override var isConnected = false

    override suspend fun connect(
        profile: ConnectionProfile,
        secret: String,
        privateKey: ByteArray?,
    ) {
        connects++
        isConnected = true
    }

    override suspend fun execute(
        command: String,
        timeoutMs: Long,
    ) = ExecResult("", "", 0)

    override suspend fun openStream(
        command: String,
        pty: Boolean,
        cols: Int,
        rows: Int,
    ): ChannelExec = error("Fake SSH has no channels")

    override suspend fun disconnect() = disconnectNow()

    override fun disconnectNow() {
        isConnected = false
    }
}

/** Answers helper requests from [replies]; a reply may be a value or a function of the arguments. */
class FakeHostLink : HostLink {
    val replies = mutableMapOf<String, (JSONObject) -> Any>()
    val requests = mutableListOf<String>()
    val lost = MutableSharedFlow<HostLinkEvent>(extraBufferCapacity = 8)
    var starts = 0
    override var isOpen = false
    override val events: SharedFlow<HostLinkEvent> = lost

    init {
        replies["profile_load"] = { emptyProfile() }
        replies["sessions"] = { JSONArray().put(session("main", "$1", "s1")) }
        replies["capabilities"] = { JSONObject().put("agents", JSONArray()) }
        replies["repositories"] = { JSONObject().put("repos", JSONArray()) }
        replies["session_context"] = { "1:2:3:4" }
        replies["browser_prepare"] = { error("No browser bridge") }
    }

    override suspend fun start(
        source: ByteArray,
        socket: String,
        browserSource: ByteArray,
        host: String,
    ): JSONObject {
        starts++
        isOpen = true
        return JSONObject().put("machine", "machine").put("user", "user")
    }

    override suspend fun request(
        method: String,
        args: JSONObject,
        timeoutMs: Long,
    ): Any {
        requests.add(method)
        check(isOpen) { "Reconnect this host first." }
        val reply = replies[method] ?: error("Unknown method $method")
        return reply(args)
    }

    override fun close() {
        isOpen = false
    }

    companion object {
        fun emptyProfile(): JSONObject = SharedProfile.empty().document

        fun session(
            name: String,
            id: String,
            stamp: String,
        ): JSONObject =
            JSONObject()
                .put("name", name)
                .put("windows", 1)
                .put("attached", 0)
                .put("id", id)
                .put("stamp", stamp)
    }
}

class FakeLinkFactory : HostLinkFactory {
    val ssh = mutableListOf<FakeSsh>()
    val hosts = mutableListOf<FakeHostLink>()

    override fun ssh(
        hostId: String,
        prompts: HostKeyPromptCoordinator,
        onBanner: (String) -> Unit,
    ): SshLink = FakeSsh().also { ssh.add(it) }

    override fun host(
        ssh: SshLink,
        scope: CoroutineScope,
        onBrowserOpen: suspend (String) -> String?,
    ): HostLink = FakeHostLink().also { hosts.add(it) }
}

class FakeHostStore : HostStore {
    val saved = linkedMapOf<String, ConnectionProfile>()
    private val drafts = mutableMapOf<String, JSONObject>()

    override fun loadAll() = saved.values.toList()

    override fun save(profile: ConnectionProfile) {
        saved[profile.id] = profile
    }

    override fun hasDraft(host: String) = host in drafts

    override fun draft(host: String) = drafts[host]

    override fun draftBase(host: String): JSONObject? = null

    override fun retainDraft(
        host: String,
        document: JSONObject,
        base: JSONObject?,
    ) {
        drafts[host] = document
    }

    override fun clearDraft(host: String) {
        drafts.remove(host)
    }
}

fun fakeRegistry(
    scope: CoroutineScope,
    factory: FakeLinkFactory = FakeLinkFactory(),
    store: FakeHostStore = FakeHostStore(),
) = HostRegistry(
    store = store,
    links = factory,
    helpers = { HelperSources(ByteArray(1), ByteArray(1)) },
    readKey = { ByteArray(1) },
    openBrowser = { null },
    scope = scope,
)

fun tailscaleProfile(id: String = "host-1") = ConnectionProfile(host = "workstation.example", username = "dev", authMethod = com.adamchesters.dazedev.mobile.data.AuthMethod.TailscaleSsh, id = id, name = "Dev")

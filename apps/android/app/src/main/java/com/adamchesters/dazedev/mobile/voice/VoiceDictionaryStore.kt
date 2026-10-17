package com.adamchesters.dazedev.mobile.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class DictionaryState(
    val deviceId: String,
    val dictionary: Dictionary = Dictionary(),
    /** The saved host (profile id) this phone syncs with; empty for this device only. */
    val syncHost: String = "",
    /** Sync was enabled after the user reviewed the host's saved dictionary once. */
    val adopted: Boolean = false,
)

data class DictionaryView(
    val state: DictionaryState,
    val status: String = "Stored on this device.",
    /** The host's saved dictionary waiting for the user to merge it. */
    val adoption: Dictionary? = null,
    val loaded: Boolean = false,
)

/** Helper requests to one saved host: host id, method and arguments. */
typealias DictionaryRequest = suspend (String, String, JSONObject) -> Any

/**
 * The phone's dictation dictionary and its sync with one host, as the desktop's dictionary store does:
 * every change is saved here first (with a backup), the host copy merges by compare-and-swap, and a host's
 * saved dictionary is merged only after the user reviewed it once.
 */
class VoiceDictionaryStore(
    private val file: File,
    private val request: DictionaryRequest,
) {
    private val serial = Mutex()
    private val current = MutableStateFlow(DictionaryView(DictionaryState(newDeviceId())))
    val view: StateFlow<DictionaryView> = current.asStateFlow()
    val dictionary get() = current.value.state.dictionary

    private fun newDeviceId() = UUID.randomUUID().toString()

    suspend fun load() =
        serial.withLock {
            if (current.value.loaded) return@withLock
            try {
                if (file.exists()) {
                    val raw = JSONObject(file.readText())
                    val device = raw.optString("deviceId")
                    if (raw.opt("schema") != 1 || !Regex("^[\\w-]{1,80}$").matches(device) || raw.opt("syncHost") !is String || raw.opt("adopted") !is Boolean) error("Invalid dictionary settings.")
                    current.value = DictionaryView(DictionaryState(device, VoiceDictionary.parse(raw.opt("dictionary")), raw.getString("syncHost"), raw.getBoolean("adopted")), loaded = true)
                } else {
                    current.update { it.copy(loaded = true) }
                }
            } catch (e: Exception) {
                current.update { it.copy(status = "Dictionary could not load: ${e.message}. The file is kept; fix or remove it, then open this again.") }
            }
        }

    private fun persist(state: DictionaryState) {
        check(current.value.loaded) { "Dictionary has not loaded. The saved file is preserved." }
        if (state == current.value.state) return
        val json =
            JSONObject()
                .put("schema", 1)
                .put("deviceId", state.deviceId)
                .put("dictionary", VoiceDictionary.toJson(state.dictionary))
                .put("syncHost", state.syncHost)
                .put("adopted", state.adopted)
        file.parentFile?.mkdirs()
        if (file.exists()) file.copyTo(File(file.path + ".bak"), overwrite = true)
        val staged = File(file.path + ".tmp")
        staged.writeText(json.toString())
        if (!staged.renameTo(file)) {
            staged.delete()
            error("Could not save the dictionary.")
        }
        current.update { it.copy(state = state) }
    }

    /** Adds or changes an entry; [expected] is the entry's revision when editing began. */
    suspend fun saveEntry(
        entry: String?,
        preferred: String,
        aliases: List<String>,
        deleted: Boolean = false,
        disabled: Boolean = false,
        expected: String? = null,
    ) = serial.withLock {
        val state = current.value.state
        val key = entry ?: UUID.randomUUID().toString()
        if (expected != null && VoiceDictionary.entryRevision(state.dictionary, key) != expected) {
            error("This entry changed on another device while you were editing. Your draft is kept. Choose Edit again to review the new version before saving.")
        }
        persist(state.copy(dictionary = VoiceDictionary.edit(state.dictionary, state.deviceId, key, preferred, aliases, deleted, disabled)))
        current.update { it.copy(status = "Saved on this phone.") }
    }

    suspend fun setHost(host: String) =
        serial.withLock {
            persist(current.value.state.copy(syncHost = host, adopted = false))
            current.update { it.copy(adoption = null, status = if (host.isNotEmpty()) "Check this host to review its saved dictionary." else "Sync off. This phone's dictionary is kept.") }
        }

    /** Merges with the sync host. Without [adopt], a host this phone has not adopted is shown for review first. */
    suspend fun sync(adopt: Boolean = false) {
        if (!current.value.loaded) return
        try {
            serial.withLock {
                var state = current.value.state
                if (state.syncHost.isEmpty()) return@withLock
                var snapshot = read(state.syncHost)
                if (!state.adopted && !adopt) {
                    current.update { it.copy(adoption = snapshot.first ?: Dictionary(), status = "Review the saved dictionary, then enable sync once for this phone.") }
                    return@withLock
                }
                repeat(3) { attempt ->
                    if (attempt > 0) snapshot = read(state.syncHost)
                    val merged = VoiceDictionary.merge(state.dictionary, snapshot.first ?: Dictionary())
                    // The merge is saved here before the host's compare-and-swap, so offline edits always survive.
                    state = state.copy(dictionary = merged, adopted = true)
                    persist(state)
                    if (snapshot.first != null && VoiceDictionary.same(merged, snapshot.first!!)) {
                        current.update { it.copy(adoption = null, status = "Up to date. Changes made offline merge when this host reconnects.") }
                        return@withLock
                    }
                    val saved = request(state.syncHost, "dictionary_write", JSONObject().put("dictionary", VoiceDictionary.toJson(merged)).put("revision", snapshot.second)) as JSONObject
                    if (saved.optBoolean("saved")) {
                        current.update { it.copy(adoption = null, status = "Synced. Changes made offline merge when this host reconnects.") }
                        return@withLock
                    }
                }
                error("The host dictionary changed during sync. Retry shortly.")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            current.update { it.copy(status = "This phone's copy is kept. Sync unavailable: ${e.message}") }
        }
    }

    private suspend fun read(host: String): Pair<Dictionary?, String> {
        val reply = request(host, "dictionary_read", JSONObject()) as JSONObject
        val value = reply.opt("dictionary")
        return (if (value == null || value == JSONObject.NULL) null else VoiceDictionary.parse(value)) to reply.optString("revision")
    }
}

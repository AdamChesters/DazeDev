package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.voice.VoiceDictionary
import com.adamchesters.dazedev.mobile.voice.VoiceDictionaryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Opens the dictionary, saves edits on this phone first, and syncs an adopted host every minute while on screen. */
class DictionaryController(
    file: File,
    private val registry: HostRegistry,
    private val scope: CoroutineScope,
) {
    val store =
        VoiceDictionaryStore(file) { hostId, method, args ->
            val h = registry.entry(hostId)?.takeIf { it.phase == ConnectionPhase.Connected } ?: error("connect ${registry.entry(hostId)?.label ?: "the sync host"} first.")
            h.link.request(method, args, 30_000)
        }
    private val open = MutableStateFlow(false)
    val isOpen = open.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private val problem = MutableStateFlow("")
    val error = problem.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            store.load()
            while (isActive) {
                delay(60_000)
                val state = store.view.value.state
                if (registry.foreground.value && state.adopted && registry.entry(state.syncHost)?.phase == ConnectionPhase.Connected) store.sync()
            }
        }
    }

    /** The voice transcript with the dictionary's preferred spellings. */
    fun apply(text: String): String = runCatching { VoiceDictionary.apply(text, store.dictionary) }.getOrDefault(text)

    fun show() {
        open.value = true
        problem.value = ""
        run {
            if (store.view.value.state.syncHost
                    .isNotEmpty()
            ) {
                store.sync()
            }
        }
    }

    fun close() {
        open.value = false
    }

    private fun run(work: suspend () -> Unit) {
        if (working.value) return
        working.value = true
        problem.value = ""
        scope.launch(Dispatchers.IO) {
            try {
                work()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem.value = e.message ?: "Could not save the dictionary."
            } finally {
                working.value = false
            }
        }
    }

    fun save(
        entry: String?,
        preferred: String,
        aliases: List<String>,
        deleted: Boolean = false,
        disabled: Boolean = false,
        expected: String? = null,
        saved: () -> Unit = {},
    ) = run {
        store.saveEntry(entry, preferred, aliases, deleted, disabled, expected)
        scope.launch { saved() }
        if (store.view.value.state.adopted) store.sync()
    }

    fun setHost(host: String) = run { store.setHost(host) }

    fun sync(adopt: Boolean = false) = run { store.sync(adopt) }
}

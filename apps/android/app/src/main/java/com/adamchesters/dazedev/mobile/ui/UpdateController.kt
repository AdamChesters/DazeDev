package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.releases.ReleaseSource
import com.adamchesters.dazedev.mobile.releases.UpdateStore
import com.adamchesters.dazedev.mobile.releases.historyJson
import com.adamchesters.dazedev.mobile.releases.mergeHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Public Android update checks and the release history. */
class UpdateController(
    private val ui: UiStore,
    private val scope: CoroutineScope,
    private val store: UpdateStore,
    private val source: ReleaseSource,
    private val version: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Shows saved and bundled history, then checks for updates once a day. */
    fun start(bundledHistory: String) {
        ui.update { it.copy(releaseHistory = mergeHistory(store.releaseHistory(), mergeHistory(bundledHistory))) }
        if (now() - store.lastUpdateCheck() > DAY_MS) checkUpdates(false)
    }

    fun checkUpdates(manual: Boolean = true) {
        if (ui.value.checkingUpdates) return
        ui.update { it.copy(checkingUpdates = true) }
        scope.launch {
            try {
                store.checkedUpdates()
                val update = withContext(io) { source.updates(version) }
                if (update != null && (manual || update.version != store.ignoredUpdate())) {
                    ui.update { it.copy(mobileUpdate = update) }
                } else if (manual) {
                    ui.message("No newer public Android release is available. Development APKs can be installed offline.")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (manual) ui.message(e.message ?: "Public releases are unavailable.")
            } finally {
                ui.update { it.copy(checkingUpdates = false) }
            }
        }
    }

    fun dismissUpdate() {
        ui.value.mobileUpdate?.let { store.ignoreUpdate(it.version) }
        ui.update { it.copy(mobileUpdate = null) }
    }

    fun refreshReleaseHistory() {
        if (ui.value.releaseHistoryStatus == CHECKING) return
        ui.update { it.copy(releaseHistoryStatus = CHECKING) }
        scope.launch {
            try {
                val json = withContext(io) { source.history() }
                val history = mergeHistory(json, ui.value.releaseHistory)
                store.saveReleaseHistory(historyJson(history))
                ui.update { it.copy(releaseHistory = history, releaseHistoryStatus = "") }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ui.update { it.copy(releaseHistoryStatus = "Showing saved release history. Public history is currently unavailable.") }
            }
        }
    }

    private companion object {
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val CHECKING = "Checking public release history…"
    }
}

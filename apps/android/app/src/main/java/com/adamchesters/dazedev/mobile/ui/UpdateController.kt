package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.releases.ReleaseSource
import com.adamchesters.dazedev.mobile.releases.UpdateStore
import com.adamchesters.dazedev.mobile.releases.expectedSha256
import com.adamchesters.dazedev.mobile.releases.historyJson
import com.adamchesters.dazedev.mobile.releases.mergeHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Public Android update checks and the release history. */
class UpdateController(
    private val ui: UiStore,
    private val scope: CoroutineScope,
    private val store: UpdateStore,
    private val source: ReleaseSource,
    private val version: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Where verified APKs wait for the package installer. */
    private val downloads: File = File(System.getProperty("java.io.tmpdir"), "dazedev-updates"),
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
        if (ui.value.updateProgress != null) return
        ui.value.mobileUpdate?.let { store.ignoreUpdate(it.version) }
        ui.update { it.copy(mobileUpdate = null, updateError = null, verifiedUpdate = null) }
    }

    /**
     * Downloads the update's APK and checks it against the release's SHA256SUMS.
     * Only a matching file is offered to the package installer.
     */
    fun downloadUpdate() {
        val update = ui.value.mobileUpdate ?: return
        if (ui.value.updateProgress != null) return
        ui.update { it.copy(updateProgress = "Reading SHA256SUMS…", updateError = null, verifiedUpdate = null) }
        scope.launch {
            try {
                val apk =
                    withContext(io) {
                        val sums = source.checksums(update) ?: error("This release has no SHA256SUMS file, so the APK cannot be checked. It was not downloaded. Use Open release page to install it yourself.")
                        val expected = expectedSha256(sums, update.fileName) ?: error("SHA256SUMS does not list ${update.fileName}, so the APK cannot be checked. It was not downloaded.")
                        downloads.mkdirs()
                        downloads.listFiles()?.forEach { it.delete() }
                        val target = File(downloads, update.fileName)
                        val actual =
                            try {
                                source.download(update, target) { done -> ui.update { it.copy(updateProgress = "Downloading… ${done / (1024 * 1024)} MB") } }
                            } catch (e: Exception) {
                                target.delete()
                                throw e
                            }
                        if (actual != expected) {
                            target.delete()
                            error("The downloaded APK does not match SHA256SUMS. It was deleted and not installed.")
                        }
                        target
                    }
                ui.update { it.copy(updateProgress = null, verifiedUpdate = apk.path) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ui.update { it.copy(updateProgress = null, updateError = e.message ?: "The update download failed.") }
            }
        }
    }

    /** The installer has the verified APK; close the update dialog. */
    fun installStarted() = ui.update { it.copy(mobileUpdate = null, verifiedUpdate = null, updateError = null) }

    fun installFailed(message: String) = ui.update { it.copy(updateError = message) }

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

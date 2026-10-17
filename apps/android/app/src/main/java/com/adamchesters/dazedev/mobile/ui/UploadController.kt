package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.HostUpload
import com.adamchesters.dazedev.mobile.host.UploadCancelledException
import com.adamchesters.dazedev.mobile.host.UploadCapacity
import com.adamchesters.dazedev.mobile.host.UploadSource
import com.adamchesters.dazedev.mobile.tmux.ShellQuoter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/** A file picked, shared or pasted on this phone. [size] is null when the provider does not say. */
data class UploadFile(
    val name: String,
    val size: Long?,
    val mime: String?,
    val open: () -> InputStream,
)

data class UploadUiState(
    val files: List<UploadFile> = emptyList(),
    /** Text shared from another app; it can be pasted into the terminal or uploaded as a file. */
    val text: String? = null,
    /** Quick paste: files go to the host's DazeDev uploads folder, at most 10 MiB in all. */
    val quick: Boolean = false,
    val hostId: String? = null,
    val folder: String = "",
    /** Destination filename when one file is uploaded. */
    val name: String = "",
    val capacity: UploadCapacity? = null,
    val checking: Boolean = false,
    val storageError: String? = null,
    val uploading: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val error: String? = null,
    val uploaded: List<String> = emptyList(),
    /** The terminal open when the upload sheet opened; a path is pasted only into that terminal. */
    val terminalKey: String? = null,
) {
    val open get() = files.isNotEmpty() || text != null
    val totalSize get() = files.sumOf { it.size ?: 0L }
}

/** Uploads files from this phone to a connected host after the user confirms host and folder. */
class UploadController(
    private val registry: HostRegistry,
    private val terminal: TerminalController,
    private val scope: CoroutineScope,
    private val message: (String) -> Unit,
    /** Copies a stream of unknown size into private storage and returns it as a sized file. */
    private val spool: suspend (UploadFile) -> UploadFile,
) {
    private val upload = MutableStateFlow(UploadUiState())
    val state: StateFlow<UploadUiState> = upload.asStateFlow()
    private var storage: Job? = null
    private var transfer: Job? = null

    @Volatile private var cancelled = false

    /** Opens the sheet for [files] or shared [text]. The open terminal's host and folder are the defaults. */
    fun offer(
        files: List<UploadFile>,
        text: String? = null,
        quick: Boolean = false,
    ) {
        if (upload.value.uploading) {
            message("Wait for the current upload to finish or cancel it.")
            return
        }
        if (files.isEmpty() && text.isNullOrEmpty()) return
        val open = terminal.openHost()
        val host = open ?: registry.all.firstOrNull { it.phase == ConnectionPhase.Connected }
        upload.value =
            UploadUiState(
                files = files.take(10),
                text = text?.take(65_536),
                quick = quick,
                hostId = host?.profile?.id,
                folder = "~",
                name = files.singleOrNull()?.name.orEmpty(),
                terminalKey = terminal.terminalKey(),
            )
        if (files.size > 10) message("Only the first 10 files are offered for upload.")
        if (open != null && !quick) {
            scope.launch {
                val directory = terminal.currentDirectory()
                if (directory != null && upload.value.folder == "~" && upload.value.hostId == open.profile.id) upload.update { it.copy(folder = directory) }
                checkStorage()
            }
        } else {
            checkStorage()
        }
    }

    fun dismiss() {
        if (upload.value.uploading) return
        storage?.cancel()
        upload.value = UploadUiState()
    }

    fun setHost(id: String) {
        if (upload.value.uploading) return
        upload.update { it.copy(hostId = id, folder = "~", uploaded = emptyList(), error = null) }
        checkStorage()
    }

    fun setFolder(folder: String) {
        if (upload.value.uploading) return
        upload.update { it.copy(folder = folder, uploaded = emptyList()) }
        checkStorage()
    }

    fun setName(name: String) {
        if (!upload.value.uploading) upload.update { it.copy(name = name, uploaded = emptyList()) }
    }

    /** Turns shared text into a file to upload. */
    fun textAsFile() {
        val text = upload.value.text ?: return
        val bytes = text.toByteArray()
        upload.update { it.copy(text = null, files = listOf(UploadFile("shared-text.txt", bytes.size.toLong(), "text/plain") { bytes.inputStream() }), name = "shared-text.txt") }
        checkStorage()
    }

    /** Checks the destination's free space; a zero-size check still reports the required headroom. */
    fun checkStorage() {
        storage?.cancel()
        val s = upload.value
        val h = registry.entry(s.hostId)?.takeIf { it.phase == ConnectionPhase.Connected }
        if (h == null || (!s.quick && s.folder.isBlank())) {
            upload.update { it.copy(capacity = null, checking = false, storageError = if (h == null) "Connect a host to upload to it." else null) }
            return
        }
        upload.update { it.copy(capacity = null, checking = true, storageError = null) }
        storage =
            scope.launch {
                delay(250)
                try {
                    val capacity = HostUpload(h.link::request).preflight(s.folder.trim(), 0, s.quick)
                    upload.update { if (it.hostId == s.hostId && it.folder == s.folder) it.copy(capacity = capacity, checking = false) else it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    upload.update { if (it.hostId == s.hostId && it.folder == s.folder) it.copy(checking = false, storageError = e.message ?: "Could not check the destination.") else it }
                }
            }
    }

    fun start() {
        val s = upload.value
        if (s.uploading || s.files.isEmpty()) return
        val h = registry.entry(s.hostId)?.takeIf { it.phase == ConnectionPhase.Connected } ?: return message("Connect the selected host first.")
        if (s.files.size == 1 && !s.quick && !HostUpload.validName(s.name.trim())) return message("Choose a filename without slashes or control characters.")
        cancelled = false
        upload.update { it.copy(uploading = true, progress = 0f, status = "Preparing…", error = null, uploaded = emptyList()) }
        transfer =
            scope.launch {
                val done = mutableListOf<String>()
                try {
                    val files = withContext(Dispatchers.IO) { s.files.map { if (it.size == null) spool(it) else it } }
                    val total = files.sumOf { it.size ?: 0L }
                    if (s.quick && total > HostUpload.MAX_PASTE) error("Quick paste is limited to 10 MiB. Use Upload file for larger files.")
                    val uploader = HostUpload(h.link::request)
                    var before = 0L
                    for ((index, file) in files.withIndex()) {
                        val name = if (files.size == 1 && !s.quick) s.name.trim() else HostUpload.cleanName(file.name, file.mime)
                        val size = file.size ?: 0L
                        val path =
                            withContext(Dispatchers.IO) {
                                uploader.upload(UploadSource(name, size, file.open), s.folder.trim(), s.quick, ::checkCancelled) { acknowledged ->
                                    val shown = before + acknowledged
                                    upload.update { it.copy(progress = if (total > 0) shown.toFloat() / total else 1f, status = "Uploading ${index + 1} of ${files.size}") }
                                }
                            }
                        done.add(path)
                        before += size
                    }
                    upload.update { it.copy(uploading = false, progress = 1f, status = "Upload complete. The terminal has not received any input.", uploaded = done.toList()) }
                    checkStorage()
                } catch (e: CancellationException) {
                    upload.update { it.copy(uploading = false, status = "", error = cancelledText(done), uploaded = done.toList()) }
                    throw e
                } catch (e: Exception) {
                    val reason = if (e is UploadCancelledException) cancelledText(done) else (e.message ?: "Upload failed.") + kept(done)
                    upload.update { it.copy(uploading = false, status = "", error = reason, uploaded = done.toList()) }
                }
            }
    }

    /** Stops sending; the helper deletes the unfinished file. */
    fun cancel() {
        cancelled = true
        transfer?.cancel()
    }

    private fun checkCancelled() {
        if (cancelled) throw UploadCancelledException()
    }

    private fun cancelledText(done: List<String>) = "Upload cancelled. No destination file was created for the unfinished file." + kept(done)

    private fun kept(done: List<String>) = if (done.isEmpty()) "" else " Completed files remain on the host: ${done.joinToString(", ")}"

    /** The uploaded paths, quoted for a POSIX shell and separated by spaces. */
    fun pathText(): String = upload.value.uploaded.joinToString(" ") { ShellQuoter.quote(it) }

    /** True when the terminal open now is the one that was open when the sheet opened. */
    fun sameTerminal(): Boolean = upload.value.terminalKey != null && upload.value.terminalKey == terminal.terminalKey()
}

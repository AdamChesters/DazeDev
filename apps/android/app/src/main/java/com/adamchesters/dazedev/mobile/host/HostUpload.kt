package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Base64

/** One helper request: method, arguments and timeout. */
typealias HostRequest = suspend (String, JSONObject, Long) -> Any

/** What upload_preflight reports about a destination folder. */
data class UploadCapacity(
    val directory: String,
    val freeBytes: Long,
    val availableBytes: Long,
    val reservedBytes: Long,
    val requiredBytes: Long,
    val maxBytes: Long,
    /** The size this capacity was checked for; required bytes include it. */
    val checkedSize: Long = 0,
) {
    /** True when a file of [size] bytes fits under the limit and the free-space headroom. */
    fun fits(size: Long) = size <= maxBytes && size + requiredBytes - checkedSize <= availableBytes
}

/** Bytes to upload, read once from the start. */
class UploadSource(
    val name: String,
    val size: Long,
    private val open: () -> InputStream,
) {
    internal fun stream() = open()
}

class UploadCancelledException : Exception("Upload cancelled. No destination file was created.")

/**
 * File uploads through the helper's upload_* methods, as the desktop sends them.
 * A host that offers `parallel` takes several chunks at once in any order; older hosts take one at a time.
 */
class HostUpload(
    private val request: HostRequest,
) {
    suspend fun preflight(
        directory: String?,
        size: Long,
        paste: Boolean = false,
    ): UploadCapacity {
        val args = JSONObject().put("size", size).put("paste", paste)
        if (!paste) args.put("directory", directory)
        return capacity(request("upload_preflight", args, 30_000) as JSONObject, size)
    }

    /**
     * Uploads [source] and returns the destination path. [current] runs before each chunk and throws to stop;
     * [progress] receives the bytes the host has acknowledged. A failed or cancelled upload is aborted on the host.
     */
    suspend fun upload(
        source: UploadSource,
        directory: String?,
        paste: Boolean = false,
        current: () -> Unit = {},
        progress: (Long) -> Unit = {},
    ): String {
        require(validName(source.name)) { "Choose a filename without slashes or control characters." }
        val limit = if (paste) MAX_PASTE else MAX_UPLOAD
        require(source.size in 0..limit) { if (paste) "Quick paste is limited to 10 MiB. Use Upload file for larger files." else "Files must be at most 256 MiB." }
        current()
        val checked = preflight(directory, source.size, paste)
        if (!checked.fits(source.size)) error("Not enough destination storage for this file and the required safety margin.")
        current()
        val args = JSONObject().put("name", source.name).put("size", source.size).put("paste", paste)
        if (!paste) args.put("directory", checked.directory)
        val transfer = request("upload_begin", args, 30_000) as JSONObject
        val id = transfer.getString("id")
        try {
            source.stream().use { input -> sendChunks(input, source.size, transfer, current, progress) }
            current()
            val result = request("upload_finish", JSONObject().put("id", id), 60_000) as JSONObject
            return result.getString("path")
        } finally {
            // Also after finish: the helper ignores an unknown id, so this never removes a published file.
            withContext(NonCancellable) { runCatching { request("upload_abort", JSONObject().put("id", id), 10_000) } }
        }
    }

    private suspend fun sendChunks(
        input: InputStream,
        size: Long,
        transfer: JSONObject,
        current: () -> Unit,
        progress: (Long) -> Unit,
    ) {
        val chunk = chunkSize(transfer)
        val id = transfer.getString("id")
        val reader = Mutex()
        var next = 0L

        // Chunks are read in offset order under one lock, so a plain stream is enough even when sends overlap.
        suspend fun take(): Pair<Long, ByteArray>? =
            reader.withLock {
                if (next >= size) return@withLock null
                val offset = next
                val bytes = readFully(input, minOf(chunk.toLong(), size - offset).toInt())
                next += bytes.size
                offset to bytes
            }

        suspend fun send(
            offset: Long,
            bytes: ByteArray,
        ): JSONObject = request("upload_chunk", JSONObject().put("id", id).put("offset", offset).put("data", Base64.getEncoder().encodeToString(bytes)), 60_000) as JSONObject
        val parallel = transfer.opt("parallel")
        if (parallel !is Number || parallel.toInt() < 1) {
            while (true) {
                current()
                val (offset, bytes) = take() ?: break
                val reply = send(offset, bytes)
                if (reply.optLong("offset", -1) != offset + bytes.size) error(MISMATCH)
                progress(offset + bytes.size)
            }
        } else {
            val lanes = minOf(parallel.toInt(), MAX_LANES, ((size + chunk - 1) / chunk).toInt())
            var acknowledged = 0L
            val progressLock = Mutex()
            coroutineScope {
                (0 until lanes)
                    .map {
                        async {
                            while (true) {
                                current()
                                val (offset, bytes) = take() ?: break
                                val received = send(offset, bytes).optLong("received", -1)
                                if (received < 0 || received > size) error(MISMATCH)
                                progressLock.withLock {
                                    if (received > acknowledged) {
                                        acknowledged = received
                                        progress(received)
                                    }
                                }
                            }
                        }
                    }.forEach { it.await() }
            }
            if (acknowledged != size) error(MISMATCH)
        }
        if (input.read() != -1) error("The file changed while it was uploading. Retry the upload.")
    }

    private fun readFully(
        input: InputStream,
        count: Int,
    ): ByteArray {
        val bytes = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = input.read(bytes, read, count - read)
            if (n < 0) error("The file changed while it was uploading. Retry the upload.")
            read += n
        }
        return bytes
    }

    companion object {
        const val MAX_UPLOAD = 256L * 1024 * 1024
        const val MAX_PASTE = 10L * 1024 * 1024

        /** Chunks kept in flight at once, whatever the host offers. The host link allows 8 requests. */
        const val MAX_LANES = 4

        /** Requests over the host link stay below 1 MiB, so a chunk is at most 512 KiB before base64. */
        const val MAX_CHUNK = 512 * 1024
        private const val MISMATCH = "Upload progress did not match. Retry the upload."

        /** The chunk size the host offered, capped for this link; older hosts take 32 KiB. */
        fun chunkSize(transfer: JSONObject): Int {
            val offered = transfer.opt("chunk")
            return if (offered is Number && offered.toLong() >= 4096) minOf(offered.toLong(), MAX_CHUNK.toLong()).toInt() else 32_768
        }

        fun validName(name: String) = name.isNotEmpty() && name != "." && name != ".." && name.length <= 255 && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }

        /** A safe default filename for shared or clipboard content. */
        fun cleanName(
            name: String?,
            mime: String?,
        ): String {
            val cleaned =
                name
                    .orEmpty()
                    .map { if (it == '/' || it == '\\' || it.code < 32 || it.code == 127) '_' else it }
                    .joinToString("")
                    .trim()
                    .take(200)
            if (validName(cleaned)) return cleaned
            val extension =
                when {
                    mime == "image/png" -> "png"
                    mime == "image/jpeg" -> "jpg"
                    mime?.startsWith("image/") == true -> mime.substringAfter('/').filter(Char::isLetterOrDigit).ifEmpty { "bin" }
                    mime == "text/plain" -> "txt"
                    mime == "application/pdf" -> "pdf"
                    else -> "bin"
                }
            return if (mime?.startsWith("image/") == true) "screenshot.$extension" else "shared.$extension"
        }

        fun capacity(
            value: JSONObject,
            size: Long,
        ): UploadCapacity {
            fun number(key: String): Long {
                val n = value.opt(key)
                if (n !is Number || n.toDouble() != n.toLong().toDouble() || n.toLong() < 0) error("The host returned invalid storage information. No upload was started.")
                return n.toLong()
            }
            val directory = value.optString("directory")
            if (directory.isEmpty()) error("The host returned invalid storage information. No upload was started.")
            val result = UploadCapacity(directory, number("freeBytes"), number("availableBytes"), number("reservedBytes"), number("requiredBytes"), number("maxBytes"), size)
            if (result.requiredBytes < size) error("The host returned invalid storage information. No upload was started.")
            return result
        }

        fun bytesLabel(bytes: Long): String {
            val units = listOf("B", "KiB", "MiB", "GiB", "TiB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024
                unit++
            }
            return if (unit == 0) "$bytes B" else String.format(java.util.Locale.ROOT, "%.2f %s", value, units[unit])
        }
    }
}

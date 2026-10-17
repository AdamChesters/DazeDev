package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import java.util.Base64

data class HostText(
    val path: String,
    val text: String,
)

data class HostImage(
    val path: String,
    val mime: String,
    val bytes: ByteArray,
)

/**
 * Reads files for previews with the helper's file_read and image_read, which resolve a relative path
 * against the terminal's folder on that same host. Replies are checked against the desktop's limits.
 */
class HostFiles(
    private val request: HostRequest,
) {
    suspend fun text(
        path: String,
        directory: String?,
    ): HostText {
        val args = JSONObject().put("path", path)
        if (!directory.isNullOrBlank()) args.put("directory", directory)
        val reply = request("file_read", args, 30_000) as JSONObject
        val text = reply.optString("text")
        if (reply.optString("path").isEmpty() || text.toByteArray().size > MAX_TEXT) error("The host returned an invalid file.")
        return HostText(reply.getString("path"), text)
    }

    suspend fun image(
        path: String,
        directory: String?,
    ): HostImage {
        var offset = 0
        var revision = ""
        var total = 0
        var mime = ""
        var canonical = path
        val out = java.io.ByteArrayOutputStream()
        do {
            val args = JSONObject().put("path", canonical).put("offset", offset)
            if (!directory.isNullOrBlank()) args.put("directory", directory)
            if (revision.isNotEmpty()) args.put("revision", revision)
            val chunk = request("image_read", args, 30_000) as JSONObject
            val bytes = checkChunk(chunk, offset, revision, total, mime)
            out.write(bytes)
            offset += bytes.size
            revision = chunk.getString("revision")
            total = chunk.getInt("size")
            mime = chunk.getString("mime")
            canonical = chunk.getString("path")
        } while (offset < total)
        return HostImage(canonical, mime, out.toByteArray())
    }

    companion object {
        /** The helper's file_read limit (MAX_DOCUMENT in host.py). */
        const val MAX_TEXT = 512 * 1024

        /** The helper's image limit and chunk size (MAX_IMAGE and IMAGE_CHUNK in host.py). */
        const val MAX_IMAGE = 16 * 1024 * 1024
        const val IMAGE_CHUNK = 49_152
        private val mimes = Regex("^image/(png|jpeg|gif|webp|bmp|x-icon|avif)$")

        /** Validates one image_read reply and returns its bytes. */
        fun checkChunk(
            chunk: JSONObject,
            offset: Int,
            revision: String,
            total: Int,
            mime: String,
        ): ByteArray {
            val size = chunk.opt("size")
            val data = chunk.opt("data")
            val chunkRevision = chunk.optString("revision")
            val chunkMime = chunk.optString("mime")
            if (size !is Int || size <= 0 || size > MAX_IMAGE || chunk.optInt("offset", -1) != offset || !mimes.matches(chunkMime) ||
                data !is String || data.length > 65_536 || chunkRevision.isEmpty() || chunk.optString("path").isEmpty() ||
                (revision.isNotEmpty() && (revision != chunkRevision || total != size || mime != chunkMime))
            ) {
                error("The host returned invalid image data.")
            }
            val bytes = Base64.getDecoder().decode(data)
            if (bytes.isEmpty() || bytes.size != minOf(IMAGE_CHUNK, size - offset)) error("Incomplete image data.")
            return bytes
        }
    }
}

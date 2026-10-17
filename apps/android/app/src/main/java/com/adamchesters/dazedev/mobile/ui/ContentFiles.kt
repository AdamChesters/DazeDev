package com.adamchesters.dazedev.mobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.adamchesters.dazedev.mobile.host.HostUpload
import java.io.File
import java.util.UUID

/** Reads picked, shared and clipboard documents for upload. Documents are read only when an upload starts. */
class ContentFiles(
    private val context: Context,
) {
    private val spoolFolder get() = File(context.cacheDir, "upload-spool")

    fun describe(uri: Uri): UploadFile {
        val resolver = context.contentResolver
        var name: String? = null
        var size: Long? = null
        try {
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it).takeIf { s -> s >= 0 } }
                }
            } finally {
                cursor?.close()
            }
        } catch (_: Exception) {
            // Name and size are optional; the upload reads the document itself.
        }
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
        return UploadFile(HostUpload.cleanName(name, mime), size, mime) { resolver.openInputStream(uri) ?: error("This file can no longer be read. Share or pick it again.") }
    }

    /** Copies a document of unknown size into private storage, up to the upload limit, so its size is known. */
    fun spool(file: UploadFile): UploadFile {
        spoolFolder.mkdirs()
        val target = File(spoolFolder, UUID.randomUUID().toString())
        try {
            var total = 0L
            file.open().use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > HostUpload.MAX_UPLOAD) error("Files must be at most 256 MiB.")
                        output.write(buffer, 0, n)
                    }
                }
            }
            return file.copy(size = total, open = { target.inputStream() })
        } catch (e: Exception) {
            target.delete()
            throw e
        }
    }

    /** Removes copies made for earlier uploads. */
    fun clearSpool() {
        spoolFolder.listFiles()?.forEach { it.delete() }
    }
}

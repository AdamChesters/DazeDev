package com.adamchesters.dazedev.mobile.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Shares or saves the open terminal's text through Android. Nothing is sent to the host. */
object TerminalExport {
    /** A file name such as `build-server-20261017-0930.txt`, safe on every storage provider. */
    fun fileName(
        terminal: String,
        time: LocalDateTime,
    ): String {
        val base =
            terminal
                .replace(Regex("[^A-Za-z0-9._-]+"), "-")
                .trim('-', '.')
                .take(60)
                .ifEmpty { "terminal" }
        return "$base-${time.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))}.txt"
    }

    /** Writes [text] to private cache and offers it to other apps; earlier exports over an hour old are removed. */
    fun share(
        context: Context,
        name: String,
        text: String,
    ) {
        val folder = File(context.cacheDir, "exports").apply { mkdirs() }
        folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
        val file = File(folder, name)
        file.writeText(text)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", file)
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, name)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(name, uri)
        context.startActivity(Intent.createChooser(send, "Share terminal output").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    fun save(
        context: Context,
        uri: Uri,
        text: String,
    ) {
        val output = context.contentResolver.openOutputStream(uri, "wt") ?: error("Could not open the chosen file.")
        output.use { it.write(text.toByteArray()) }
    }
}

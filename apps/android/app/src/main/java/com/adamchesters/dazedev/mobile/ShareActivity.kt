package com.adamchesters.dazedev.mobile

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle

/**
 * Receives "Share to DazeDev" and hands the items to the open app, so a share never starts a second
 * copy of the app inside the sharing app's task. Nothing is uploaded until the user confirms it.
 */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val forward = forward(intent)
        if (forward != null) runCatching { startActivity(forward) }
        finish()
    }

    private fun forward(source: Intent?): Intent? {
        if (source == null || (source.action != Intent.ACTION_SEND && source.action != Intent.ACTION_SEND_MULTIPLE)) return null
        val uris = streams(source).filter { it.scheme == "content" }.distinct().take(MAX_ITEMS)
        val text = source.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.take(65_536)
        if (uris.isEmpty() && text.isNullOrEmpty()) return null
        val intent =
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_SHARED)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (text != null) intent.putExtra(Intent.EXTRA_TEXT, text)
        if (uris.isNotEmpty()) {
            // The read grant travels with the clip to the app's main screen.
            val clip = ClipData.newUri(contentResolver, "Shared", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return intent
    }

    @Suppress("DEPRECATION")
    private fun streams(source: Intent): List<Uri> =
        if (source.action == Intent.ACTION_SEND) {
            listOfNotNull(if (Build.VERSION.SDK_INT >= 33) source.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else source.getParcelableExtra(Intent.EXTRA_STREAM))
        } else {
            (if (Build.VERSION.SDK_INT >= 33) source.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else source.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        }

    companion object {
        const val ACTION_SHARED = "com.adamchesters.dazedev.mobile.action.SHARED"
        const val MAX_ITEMS = 10

        /** The shared content URIs and text that [ShareActivity] forwarded, or null for any other intent. */
        fun shared(intent: Intent?): Pair<List<Uri>, String?>? {
            if (intent?.action != ACTION_SHARED) return null
            val clip = intent.clipData
            val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }.filter { it.scheme == "content" }.take(MAX_ITEMS)
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            return if (uris.isEmpty() && text.isNullOrEmpty()) null else uris to text
        }
    }
}

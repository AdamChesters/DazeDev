package com.adamchesters.dazedev.mobile.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

object BrowserLauncher {
    fun open(context: Context, url: String): String? = try {
        val validated = BrowserUrl.validate(url)
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(validated))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) {
        "Install or enable a browser on this phone, then open the link again."
    } catch (_: Exception) {
        "This link could not be opened. Use a valid HTTP or HTTPS link."
    }
}

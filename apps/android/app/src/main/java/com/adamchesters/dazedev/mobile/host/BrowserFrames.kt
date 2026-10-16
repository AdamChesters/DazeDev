package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject

data class BrowserOpen(
    val id: String,
    val url: String,
)

object BrowserFrames {
    fun parse(frame: JSONObject): BrowserOpen? {
        if (frame.optString("event") != "browser-open") return null
        val id = frame.opt("id") as? String ?: return null
        val url = frame.opt("url") as? String ?: return null
        if (!Regex("[a-f0-9]{32}").matches(id) || url.length !in 1..8192) return null
        if (url.any { it.code < 32 || it.code == 127 }) return null
        return BrowserOpen(id, url)
    }
}

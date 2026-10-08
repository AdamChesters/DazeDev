package com.adamchesters.dazedev.mobile.host

import java.util.Base64

class HostFrames(private val nonce: String) {
    private val body = StringBuilder()
    private var next = 0
    private var total = 0
    fun line(line: String): String? {
        val prefix = "DAZED:$nonce:"
        if (!line.startsWith(prefix)) return null
        val match = Regex("@([0-9]+)/([0-9]+):([A-Za-z0-9+/=]*)").matchEntire(line.removePrefix(prefix)) ?: return null
        val index = match.groupValues[1].toIntOrNull() ?: error("Invalid frame index")
        val count = match.groupValues[2].toIntOrNull() ?: error("Invalid frame count")
        require(count in 1..30_000 && index == next && (index == 0 || count == total)) { "Out-of-order host frame" }
        if (index == 0) total = count
        body.append(match.groupValues[3])
        require(body.length <= 1_400_000) { "Host reply exceeded 1 MiB" }
        next++
        if (next != total) return null
        val decoded = Base64.getDecoder().decode(body.toString())
        body.setLength(0); next = 0; total = 0
        require(decoded.size <= 1_048_576) { "Host reply exceeded 1 MiB" }
        return decoded.toString(Charsets.UTF_8)
    }
}

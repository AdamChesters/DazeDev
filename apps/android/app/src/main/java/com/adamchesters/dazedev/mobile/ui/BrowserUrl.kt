package com.adamchesters.dazedev.mobile.ui

import java.net.URI

object BrowserUrl {
    fun validate(value: String): String {
        require(value.isNotBlank() && value.length <= 8192 && value.none { it.code <= 32 || it.code == 127 }) {
            "Choose one HTTP or HTTPS link without spaces or control characters."
        }
        val uri = URI(value)
        require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535) {
            "Only HTTP and HTTPS links without embedded credentials can be opened."
        }
        val host =
            uri.host
                .lowercase()
                .trim('[', ']')
                .trimEnd('.')
        require(host !in setOf("localhost", "0.0.0.0", "::", "::1") && !host.endsWith(".localhost") && !host.startsWith("127.")) {
            "This link points to the remote host itself. Use the program's device-code or remote login flow."
        }
        return value
    }
}

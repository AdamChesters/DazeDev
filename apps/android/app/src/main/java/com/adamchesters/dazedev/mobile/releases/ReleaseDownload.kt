package com.adamchesters.dazedev.mobile.releases

import java.io.OutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection

/** Downloads public release assets over HTTPS, following GitHub's redirect to its asset store. */
object ReleaseDownload {
    private val hosts = Regex("^(github\\.com|[a-z0-9-]+\\.githubusercontent\\.com)$")

    /** True for the HTTPS hosts a release asset may come from. */
    fun allowed(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && hosts.matches(uri.host.orEmpty())
    }

    fun fetch(
        url: String,
        out: OutputStream,
        maxBytes: Long,
        progress: (Long) -> Unit,
    ) {
        var next = url
        repeat(5) {
            require(allowed(next)) { "The update download left GitHub. It was stopped." }
            val connection = URI(next).toURL().openConnection() as HttpsURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "DazeDev-Mobile")
            try {
                val code = connection.responseCode
                if (code in 300..399) {
                    next = URI(next).resolve(connection.getHeaderField("Location") ?: error("Download redirect without a target")).toString()
                    return@repeat
                }
                require(code == 200) { "The update download failed ($code)." }
                require(connection.contentLengthLong <= maxBytes) { "The update download is too large." }
                connection.inputStream.use { input ->
                    val buffer = ByteArray(65536)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= maxBytes) { "The update download is too large." }
                        out.write(buffer, 0, n)
                        progress(total)
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("Too many download redirects.")
    }
}

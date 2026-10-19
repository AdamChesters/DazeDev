package com.adamchesters.dazedev.mobile.releases

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import javax.net.ssl.HttpsURLConnection

private const val REPOSITORY = "AdamChesters/DazeDev"
private const val MAX_METADATA = 1024 * 1024
private const val MAX_SUMS = 64 * 1024
private val MOBILE_APK = Regex("^DazeDev-Mobile-(.+)\\.apk$")

// Android releases attach SHA256SUMS; desktop releases attach SHA256SUMS.txt.
private val SUMS_NAMES = listOf("SHA256SUMS", "SHA256SUMS.txt")

data class ReleaseEntry(
    val platform: String,
    val version: String,
    val date: String,
    val notes: String,
    val channel: String = "",
)

data class MobileUpdate(
    val version: String,
    val url: String,
    val notes: String,
    /** The release's SHA256SUMS (or SHA256SUMS.txt) asset, or null when the release has none. */
    val checksums: String? = null,
    val page: String = "",
) {
    val fileName get() = url.substringAfterLast('/')
}

/** Finds [name]'s SHA-256 in a sha256sum-style file ("<hex>  <name>" or "<hex> *<name>"). */
fun expectedSha256(
    sums: String,
    name: String,
): String? =
    sums
        .lineSequence()
        .mapNotNull { Regex("^([0-9a-fA-F]{64}) [ *](.+)$").matchEntire(it.trimEnd('\r')) }
        .firstOrNull { it.groupValues[2] == name }
        ?.groupValues
        ?.get(1)
        ?.lowercase()

private fun releaseAsset(
    asset: JSONObject,
    tag: String,
    name: String,
    maxSize: Long,
): String? {
    if (asset.optString("name") != name || asset.optLong("size") !in 1..maxSize) return null
    val url = asset.optString("browser_download_url")
    val uri = runCatching { URI(url) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.host != "github.com" || uri.userInfo != null || uri.port != -1 || uri.query != null || uri.fragment != null) return null
    return url.takeIf { uri.path == "/$REPOSITORY/releases/download/$tag/$name" }
}

fun mergeHistory(
    remote: String,
    saved: List<ReleaseEntry> = emptyList(),
): List<ReleaseEntry> {
    val result = saved.associateBy { "${it.platform}:${it.version}" }.toMutableMap()
    val array = runCatching { JSONArray(remote) }.getOrNull() ?: return saved
    if (array.length() > 500) return saved
    for (i in 0 until array.length()) {
        val entry = array.optJSONObject(i) ?: continue
        val platform = entry.optString("platform")
        val version = entry.optString("version")
        val date = entry.optString("date")
        val notes = entry.optString("notes")
        if (platform !in listOf("desktop", "mobile") || version.length !in 1..80 || notes.length > 50000 || runCatching { java.time.LocalDate.parse(date) }.isFailure) continue
        result["$platform:$version"] = ReleaseEntry(platform, version, date, notes, entry.optString("channel"))
    }
    return result.values.sortedByDescending { it.date }
}

fun historyJson(entries: List<ReleaseEntry>): String =
    JSONArray(
        entries.map {
            JSONObject()
                .put("platform", it.platform)
                .put("version", it.version)
                .put("date", it.date)
                .put("notes", it.notes)
                .put("channel", it.channel)
        },
    ).toString()

private data class Version(
    val core: List<Int>,
    val pre: List<String>,
) : Comparable<Version> {
    override fun compareTo(other: Version): Int {
        core.zip(other.core).forEach { (a, b) -> if (a != b) return a.compareTo(b) }
        if (pre.isEmpty() || other.pre.isEmpty()) {
            return when {
                pre == other.pre -> 0
                pre.isEmpty() -> 1
                else -> -1
            }
        }
        pre.zip(other.pre).forEach { (a, b) ->
            if (a != b) {
                val an = a.all(Char::isDigit)
                val bn = b.all(Char::isDigit)
                return when {
                    an && bn -> if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)
                    an -> -1
                    bn -> 1
                    else -> a.compareTo(b)
                }
            }
        }
        return pre.size.compareTo(other.pre.size)
    }

    companion object {
        fun parse(value: String): Version? {
            val match = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+[0-9A-Za-z.-]+)?$").matchEntire(value) ?: return null
            val core = (1..3).map { match.groupValues[it].toIntOrNull() ?: return null }
            val pre = match.groupValues[4].takeIf { it.isNotEmpty() }?.split('.') ?: emptyList()
            if (pre.any { it.length > 1 && it.all(Char::isDigit) && it.startsWith('0') }) return null
            return Version(core, pre)
        }
    }
}

fun findMobileUpdate(
    json: String,
    current: String,
): MobileUpdate? {
    val installed = Version.parse(current) ?: return null
    val releases = JSONArray(json)
    require(releases.length() <= 100) { "Too many releases" }
    val candidates = mutableListOf<Pair<Version, MobileUpdate>>()
    for (i in 0 until releases.length()) {
        val release = releases.optJSONObject(i) ?: continue
        val tag = release.optString("tag_name")
        if (release.optBoolean("draft")) continue
        val assets = release.optJSONArray("assets") ?: continue
        val list = (0 until assets.length()).mapNotNull(assets::optJSONObject)
        // Android releases name the APK's version in the tag. Desktop releases (v…) may carry
        // the APK beside the desktop app, so their APK file name gives the Android version.
        val labels =
            when {
                tag.startsWith("android/v") -> listOf(tag.removePrefix("android/v"))
                tag.startsWith("v") -> list.mapNotNull { MOBILE_APK.matchEntire(it.optString("name"))?.groupValues?.get(1) }
                else -> emptyList()
            }
        for (label in labels) {
            val version = Version.parse(label) ?: continue
            if (version <= installed || (installed.pre.isEmpty() && (version.pre.isNotEmpty() || release.optBoolean("prerelease")))) continue
            val apk = list.firstNotNullOfOrNull { releaseAsset(it, tag, "DazeDev-Mobile-$label.apk", 512L * 1024 * 1024) } ?: continue
            val sums = SUMS_NAMES.firstNotNullOfOrNull { name -> list.firstNotNullOfOrNull { releaseAsset(it, tag, name, MAX_SUMS.toLong()) } }
            candidates.add(version to MobileUpdate(label, apk, release.optString("body").take(50000), sums, "https://github.com/$REPOSITORY/releases/tag/$tag"))
        }
    }
    return candidates.maxByOrNull { it.first }?.second
}

object PublicReleaseCatalog : ReleaseSource {
    // Fixed public endpoints, no token lookup and no authenticated fallback.
    private fun fetch(url: String): String {
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 5000
        connection.readTimeout = 10000
        connection.setRequestProperty("User-Agent", "DazeDev-Mobile")
        connection.setRequestProperty("Accept", "application/json")
        try {
            require(connection.responseCode == 200) { "Public release information is unavailable (${connection.responseCode}). Development APKs can still be installed offline." }
            val bytes =
                connection.inputStream.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= MAX_METADATA) {
                        val count = input.read(buffer, 0, minOf(buffer.size, MAX_METADATA + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            require(bytes.size <= MAX_METADATA) { "Release information is too large" }
            return bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    override fun updates(current: String): MobileUpdate? = findMobileUpdate(fetch("https://api.github.com/repos/$REPOSITORY/releases?per_page=100"), current)

    override fun history(): String = fetch("https://raw.githubusercontent.com/$REPOSITORY/main/shared/releases/changelog.json")

    override fun checksums(update: MobileUpdate): String? {
        val url = update.checksums ?: return null
        val out = java.io.ByteArrayOutputStream()
        ReleaseDownload.fetch(url, out, MAX_SUMS.toLong()) {}
        return out.toString(Charsets.UTF_8.name())
    }

    override fun download(
        update: MobileUpdate,
        target: java.io.File,
        progress: (Long) -> Unit,
    ): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        target.outputStream().use { file ->
            ReleaseDownload.fetch(update.url, java.security.DigestOutputStream(file, digest), 512L * 1024 * 1024, progress)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

package com.adamchesters.dazedev.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class ProfileStore(
    private val context: Context,
) {
    private val prefs = context.getSharedPreferences("dazedev_mobile_hosts", Context.MODE_PRIVATE)

    fun loadAll(): List<ConnectionProfile> =
        runCatching {
            val array = JSONArray(prefs.getString("hosts", "[]"))
            (0 until array.length()).map { index ->
                val p = array.getJSONObject(index)
                ConnectionProfile(
                    host = p.getString("host"),
                    port = p.getInt("port"),
                    username = p.getString("username"),
                    authMethod = AuthMethod.valueOf(p.getString("authMethod")),
                    privateKeyUri = p.optString("privateKeyUri").ifBlank { null },
                    privateKeyName = p.optString("privateKeyName").ifBlank { null },
                    workingDirectory = p.optString("workingDirectory"),
                    id = p.getString("id"),
                    name = p.optString("name"),
                    socket = p.optString("socket"),
                    fingerprint = p.optString("fingerprint"),
                )
            }
        }.getOrDefault(emptyList())

    var secureScreen: Boolean
        get() = prefs.getBoolean("secure_screen", false)
        set(value) {
            prefs.edit().putBoolean("secure_screen", value).apply()
        }

    fun load(): ConnectionProfile = loadAll().firstOrNull() ?: ConnectionProfile()

    fun save(profile: ConnectionProfile) {
        val existing = loadAll()
        val hosts = if (existing.any { it.id == profile.id }) existing.map { if (it.id == profile.id) profile else it } else existing + profile
        val a = JSONArray()
        for (p in hosts) {
            a.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("host", p.host)
                    .put("port", p.port)
                    .put("username", p.username)
                    .put("authMethod", p.authMethod.name)
                    .put("privateKeyUri", p.privateKeyUri)
                    .put("privateKeyName", p.privateKeyName)
                    .put("workingDirectory", p.workingDirectory)
                    .put("socket", p.socket)
                    .put("fingerprint", p.fingerprint),
            )
        }
        prefs.edit().putString("hosts", a.toString()).apply()
        val retained = hosts.mapNotNull { it.privateKeyUri }.toSet()
        existing.mapNotNull { it.privateKeyUri }.filterNot { it in retained }.distinct().forEach { uri ->
            runCatching { context.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    fun releaseHistory(): String = prefs.getString("release-history", "[]") ?: "[]"

    fun localWorkspaces(): JSONObject? {
        for (key in listOf("local-workspaces", "local-workspaces-last-good")) {
            val raw = prefs.getString(key, null) ?: continue
            val parsed =
                runCatching {
                    com.adamchesters.dazedev.mobile.host
                        .SharedProfile(JSONObject(raw))
                        .document
                }.getOrNull()
            if (parsed != null) return parsed
        }
        return null // Invalid originals remain stored for recovery.
    }

    fun saveLocalWorkspaces(value: JSONObject) {
        val validated =
            com.adamchesters.dazedev.mobile.host
                .SharedProfile(JSONObject(value.toString()))
                .document
        val editor = prefs.edit()
        localWorkspaces()?.let { editor.putString("local-workspaces-last-good", it.toString()) }
        check(editor.putString("local-workspaces", validated.toString()).commit()) { "Could not save workspace; previous settings retained." }
    }

    fun saveReleaseHistory(value: String) {
        prefs.edit().putString("release-history", value).apply()
    }

    fun lastUpdateCheck(): Long = prefs.getLong("last-update-check", 0L)

    fun checkedUpdates() {
        prefs.edit().putLong("last-update-check", System.currentTimeMillis()).apply()
    }

    fun ignoredUpdate(): String = prefs.getString("ignored-update", "") ?: ""

    fun ignoreUpdate(version: String) {
        prefs.edit().putString("ignored-update", version).apply()
    }

    fun launchOperation(
        host: String,
        request: String,
    ): String {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(request.toByteArray())
                .joinToString("") { "%02x".format(it) }
        val key = "launch:$host:$digest"
        return prefs.getString(key, null) ?: java.util.UUID
            .randomUUID()
            .toString()
            .also { prefs.edit().putString(key, it).apply() }
    }

    fun completeLaunch(
        host: String,
        request: String,
    ) {
        val digest =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(request.toByteArray())
                .joinToString("") { "%02x".format(it) }
        prefs.edit().remove("launch:$host:$digest").apply()
    }

    fun retainDraft(
        host: String,
        document: JSONObject,
        base: JSONObject? = null,
    ) {
        val edit = prefs.edit().putString("draft:$host", document.toString())
        if (base != null) edit.putString("draft-base:$host", base.toString())
        edit.apply()
    }

    fun draftBase(host: String): JSONObject? = prefs.getString("draft-base:$host", null)?.let(::JSONObject)

    fun draft(host: String): JSONObject? = prefs.getString("draft:$host", null)?.let(::JSONObject)

    fun clearDraft(host: String) {
        prefs
            .edit()
            .remove("draft:$host")
            .remove("draft-base:$host")
            .apply()
    }
}

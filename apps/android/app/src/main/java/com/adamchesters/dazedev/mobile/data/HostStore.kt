package com.adamchesters.dazedev.mobile.data

import org.json.JSONObject

/** Saved hosts and workspace drafts, as the host registry uses them. */
interface HostStore {
    fun loadAll(): List<ConnectionProfile>

    fun save(profile: ConnectionProfile)

    fun hasDraft(host: String): Boolean

    fun draft(host: String): JSONObject?

    fun draftBase(host: String): JSONObject?

    fun retainDraft(
        host: String,
        document: JSONObject,
        base: JSONObject? = null,
    )

    fun clearDraft(host: String)
}

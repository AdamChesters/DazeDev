package com.adamchesters.dazedev.mobile.releases

/** Saved update-check state. */
interface UpdateStore {
    fun releaseHistory(): String

    fun saveReleaseHistory(value: String)

    fun lastUpdateCheck(): Long

    fun checkedUpdates()

    fun ignoredUpdate(): String

    fun ignoreUpdate(version: String)
}

/** Where public release information comes from. */
interface ReleaseSource {
    fun updates(current: String): MobileUpdate?

    fun history(): String
}

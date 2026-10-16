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

    /** The text of the release's SHA256SUMS asset, or null when the release has none. */
    fun checksums(update: MobileUpdate): String?

    /** Downloads the APK to [target] and returns its SHA-256 as lowercase hex. */
    fun download(
        update: MobileUpdate,
        target: java.io.File,
        progress: (Long) -> Unit,
    ): String
}

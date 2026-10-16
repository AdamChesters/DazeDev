package com.adamchesters.dazedev.mobile.data

/** Private keys and remembered passphrases for saved hosts. */
interface Credentials {
    /** The private key for [profile]; the caller clears it after use. */
    suspend fun key(profile: ConnectionProfile): ByteArray

    /** The remembered passphrase, or null; the caller clears it after use. */
    suspend fun passphrase(hostId: String): CharArray?

    /** Encrypts and stores a key the user picked. */
    suspend fun importKey(
        hostId: String,
        key: ByteArray,
    )

    /**
     * After a successful connection: moves a key still read from its document into encrypted storage, and
     * stores or forgets the passphrase as the profile asks. Returns the profile to save.
     */
    suspend fun connected(
        profile: ConnectionProfile,
        key: ByteArray?,
        secret: CharArray,
    ): ConnectionProfile

    /** Deletes everything stored for a removed host. */
    suspend fun forget(hostId: String)
}

package com.adamchesters.dazedev.mobile.data

import android.content.Context
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/** Keys and passphrases encrypted with Android Keystore in app-private storage. */
class StoredCredentials(
    private val context: Context,
    private val keys: AndroidKeys = AndroidKeys(),
) : Credentials {
    private val files = KeyFiles(File(context.noBackupFilesDir, "keys"), SecretBox(keys))

    override suspend fun key(profile: ConnectionProfile): ByteArray =
        withContext(Dispatchers.IO) {
            if (profile.keyStored) return@withContext files.readKey(profile.id)
            // Profiles saved before keys were stored still name a document; it moves into storage after connecting.
            val uri = requireNotNull(profile.privateKeyUri).toUri()
            context.contentResolver.openInputStream(uri)?.use(::readLimited) ?: error("Could not open this private key.")
        }

    override suspend fun passphrase(hostId: String): CharArray? = withContext(Dispatchers.IO) { files.readPassphrase(hostId) }

    override suspend fun importKey(
        hostId: String,
        key: ByteArray,
    ) = withContext(Dispatchers.IO) { files.storeKey(hostId, key) }

    /** Reads a picked key document once. */
    suspend fun readDocument(uri: String): ByteArray =
        withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri.toUri())?.use(::readLimited) ?: error("Could not open this private key.")
        }

    override suspend fun connected(
        profile: ConnectionProfile,
        key: ByteArray?,
        secret: CharArray,
    ): ConnectionProfile =
        withContext(Dispatchers.IO) {
            if (profile.authMethod != AuthMethod.PrivateKey) return@withContext profile
            var result = profile
            if (!profile.keyStored && key != null) {
                files.storeKey(profile.id, key)
                result = profile.copy(keyStored = true, privateKeyUri = null)
            }
            if (!profile.rememberPassphrase) {
                files.forgetPassphrase(profile.id)
            } else if (secret.isNotEmpty()) {
                files.storePassphrase(profile.id, secret)
            }
            result
        }

    override suspend fun forget(hostId: String) =
        withContext(Dispatchers.IO) {
            files.delete(hostId)
            File(context.filesDir, "ssh/$hostId").deleteRecursively()
            Unit
        }

    /** Deletes stored keys and Keystore aliases that belong to no saved host. */
    suspend fun prune(saved: Set<String>) =
        withContext(Dispatchers.IO) {
            files.hosts().filterNot { it in saved }.forEach(files::delete)
            keys.aliases().filter { it.startsWith(ALIAS_PREFIX) && it.removePrefix(ALIAS_PREFIX) !in saved }.forEach(keys::delete)
        }

    private fun readLimited(input: InputStream): ByteArray {
        val buffer = ByteArray(MAX_KEY + 1)
        try {
            var total = 0
            while (total < buffer.size) {
                val count = input.read(buffer, total, buffer.size - total)
                if (count < 0) break
                total += count
            }
            require(total <= MAX_KEY) { "Private key exceeds 1 MiB." }
            return buffer.copyOf(total)
        } finally {
            buffer.fill(0)
        }
    }

    private companion object {
        const val MAX_KEY = 1_048_576
        const val ALIAS_PREFIX = "dazedev-host-"
    }
}

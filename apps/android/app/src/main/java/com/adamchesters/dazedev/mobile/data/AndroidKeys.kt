package com.adamchesters.dazedev.mobile.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** AES-256 keys in Android Keystore. No user authentication, so reconnects can run unattended. */
class AndroidKeys : KeyProvider {
    private val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    @Synchronized
    override fun key(
        alias: String,
        create: Boolean,
    ): SecretKey? {
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    @Synchronized
    override fun delete(alias: String) {
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }

    @Synchronized
    fun aliases(): List<String> = store.aliases().toList()

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
    }
}

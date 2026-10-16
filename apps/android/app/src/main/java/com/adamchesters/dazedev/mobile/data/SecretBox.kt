package com.adamchesters.dazedev.mobile.data

import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Gives the AES-256 key for an alias. The app uses Android Keystore; tests use a software key. */
interface KeyProvider {
    /** The key for [alias], created when [create] is set; null when it does not exist. */
    fun key(
        alias: String,
        create: Boolean,
    ): SecretKey?

    fun delete(alias: String)
}

class SecretBoxException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Encrypts small secrets with AES-256-GCM. A sealed value is: format version (1 byte),
 * IV length (1 byte), IV, then ciphertext with its 128-bit tag. The alias is authenticated as associated data,
 * so a value cannot be moved to another host.
 */
class SecretBox(
    private val keys: KeyProvider,
) {
    fun seal(
        alias: String,
        plain: ByteArray,
    ): ByteArray {
        val key = keys.key(alias, create = true) ?: throw SecretBoxException("Could not create a device key.")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(alias.toByteArray())
            val iv = cipher.iv
            val body = cipher.doFinal(plain)
            return byteArrayOf(VERSION, iv.size.toByte()) + iv + body
        } catch (e: GeneralSecurityException) {
            throw SecretBoxException("Could not encrypt this secret on this phone.", e)
        }
    }

    fun open(
        alias: String,
        sealed: ByteArray,
    ): ByteArray {
        if (sealed.size < 2 || sealed[0] != VERSION) throw SecretBoxException("The stored secret has an unknown format. Choose the key file again.")
        val ivLength = sealed[1].toInt() and 0xff
        if (ivLength !in 12..16 || sealed.size < 2 + ivLength + TAG_BYTES) throw SecretBoxException("The stored secret is damaged. Choose the key file again.")
        val key = keys.key(alias, create = false) ?: throw SecretBoxException("The device key for this secret is gone. Choose the key file again.")
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, sealed, 2, ivLength))
            cipher.updateAAD(alias.toByteArray())
            return cipher.doFinal(sealed, 2 + ivLength, sealed.size - 2 - ivLength)
        } catch (e: GeneralSecurityException) {
            throw SecretBoxException("The stored secret could not be decrypted. Choose the key file again.", e)
        }
    }

    fun delete(alias: String) = keys.delete(alias)

    companion object {
        const val VERSION: Byte = 1
        const val TAG_BYTES = 16
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

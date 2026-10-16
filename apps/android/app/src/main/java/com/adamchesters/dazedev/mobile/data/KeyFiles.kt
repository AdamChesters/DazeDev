package com.adamchesters.dazedev.mobile.data

import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets

/** Private keys and remembered passphrases, encrypted per host in app-private storage. */
class KeyFiles(
    private val folder: File,
    private val box: SecretBox,
) {
    private fun alias(host: String) = "dazedev-host-$host"

    private fun file(
        host: String,
        kind: String,
    ): File {
        require(host.matches(Regex("[A-Za-z0-9-]{1,64}"))) { "Invalid host id." }
        return File(folder, "$host.$kind")
    }

    private fun write(
        target: File,
        bytes: ByteArray,
    ) {
        folder.mkdirs()
        val temp = File(folder, "${target.name}.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not save the encrypted key.")
        }
    }

    fun hasKey(host: String) = file(host, "key").isFile

    fun storeKey(
        host: String,
        key: ByteArray,
    ) = write(file(host, "key"), box.seal(alias(host), key))

    /** The decrypted key; the caller clears it after use. */
    fun readKey(host: String): ByteArray {
        val sealed = file(host, "key").takeIf { it.isFile }?.readBytes() ?: throw SecretBoxException("The stored key is missing. Choose the key file again.")
        return box.open(alias(host), sealed)
    }

    fun hasPassphrase(host: String) = file(host, "pass").isFile

    fun storePassphrase(
        host: String,
        passphrase: CharArray,
    ) {
        val encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
        try {
            write(file(host, "pass"), box.seal(alias(host), bytes))
        } finally {
            bytes.fill(0)
            if (encoded.hasArray()) encoded.array().fill(0)
        }
    }

    /** The remembered passphrase, or null; the caller clears it after use. */
    fun readPassphrase(host: String): CharArray? {
        val sealed = file(host, "pass").takeIf { it.isFile }?.readBytes() ?: return null
        val bytes = box.open(alias(host), sealed)
        try {
            val chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(bytes))
            return CharArray(chars.remaining()).also {
                chars.get(it)
                if (chars.hasArray()) chars.array().fill('\u0000')
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun forgetPassphrase(host: String) {
        file(host, "pass").delete()
    }

    /** Deletes the host's files and its Keystore alias. */
    fun delete(host: String) {
        file(host, "key").delete()
        file(host, "pass").delete()
        box.delete(alias(host))
    }

    /** Hosts that have stored files. */
    fun hosts(): Set<String> =
        folder
            .listFiles()
            .orEmpty()
            .mapNotNull { f -> f.name.substringBeforeLast('.').takeIf { f.name.endsWith(".key") || f.name.endsWith(".pass") } }
            .toSet()
}

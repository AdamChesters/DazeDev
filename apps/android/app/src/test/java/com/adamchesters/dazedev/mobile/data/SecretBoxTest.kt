package com.adamchesters.dazedev.mobile.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** A software AES key store standing in for Android Keystore. */
class FakeKeyProvider : KeyProvider {
    val keys = mutableMapOf<String, SecretKey>()

    override fun key(
        alias: String,
        create: Boolean,
    ): SecretKey? =
        keys[alias] ?: if (create) {
            KeyGenerator
                .getInstance("AES")
                .apply { init(256) }
                .generateKey()
                .also { keys[alias] = it }
        } else {
            null
        }

    override fun delete(alias: String) {
        keys.remove(alias)
    }
}

class SecretBoxTest {
    @get:Rule val folder = TemporaryFolder()
    private val provider = FakeKeyProvider()
    private val box = SecretBox(provider)
    private val secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n".toByteArray()

    @Test fun sealedValuesHaveTheDocumentedFormatAndRoundTrip() {
        val sealed = box.seal("dazedev-host-a", secret)
        assertEquals(SecretBox.VERSION, sealed[0])
        assertEquals(12, sealed[1].toInt())
        assertEquals(2 + 12 + secret.size + SecretBox.TAG_BYTES, sealed.size)
        assertFalse(String(sealed).contains("PRIVATE KEY"))
        assertArrayEquals(secret, box.open("dazedev-host-a", sealed))
        // A fresh IV each time.
        assertFalse(sealed.contentEquals(box.seal("dazedev-host-a", secret)))
    }

    @Test fun tamperingWrongAliasAndUnknownFormatsAreRefused() {
        val sealed = box.seal("dazedev-host-a", secret)
        val flipped = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-a", flipped) }
        provider.keys["dazedev-host-b"] = provider.keys.getValue("dazedev-host-a")
        assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-b", sealed) }
        assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-a", byteArrayOf(2) + sealed.copyOfRange(1, sealed.size)) }
        assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-a", sealed.copyOf(10)) }
        assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-a", ByteArray(0)) }
    }

    @Test fun aMissingDeviceKeyIsReportedAndNotRecreated() {
        val sealed = box.seal("dazedev-host-a", secret)
        box.delete("dazedev-host-a")
        val error = assertThrows(SecretBoxException::class.java) { box.open("dazedev-host-a", sealed) }
        assertTrue(error.message!!.contains("device key"))
        assertNull(provider.keys["dazedev-host-a"])
    }

    @Test fun keyFilesStoreKeysAndPassphrasesEncryptedPerHost() {
        val files = KeyFiles(folder.root, box)
        files.storeKey("host-1", secret)
        files.storePassphrase("host-1", "pässphrase".toCharArray())
        assertTrue(files.hasKey("host-1"))
        assertFalse(folder.root.listFiles()!!.any { String(it.readBytes()).contains("PRIVATE KEY") || String(it.readBytes()).contains("phrase") })
        assertArrayEquals(secret, files.readKey("host-1"))
        assertArrayEquals("pässphrase".toCharArray(), files.readPassphrase("host-1"))
        assertEquals(setOf("host-1"), files.hosts())
        files.forgetPassphrase("host-1")
        assertNull(files.readPassphrase("host-1"))
        files.delete("host-1")
        assertFalse(files.hasKey("host-1"))
        assertNull(provider.keys["dazedev-host-host-1"])
        assertThrows(SecretBoxException::class.java) { files.readKey("host-1") }
        assertThrows(IllegalArgumentException::class.java) { files.readKey("../escape") }
    }
}

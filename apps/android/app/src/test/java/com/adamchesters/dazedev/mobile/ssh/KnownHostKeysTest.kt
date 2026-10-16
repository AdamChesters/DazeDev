package com.adamchesters.dazedev.mobile.ssh

import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class KnownHostKeysTest {
    private fun blob(type:String,body:String):ByteArray {
        val t=type.toByteArray();val b=body.toByteArray()
        return ByteBuffer.allocate(8+t.size+b.size).putInt(t.size).put(t).putInt(b.size).put(b).array()
    }
    @Test fun listsAndForgetsOneKey() {
        val file=File.createTempFile("known","hosts")
        try {
            val first=blob("ssh-ed25519","one");val second=blob("ssh-rsa","two")
            file.writeText("|1|abc|def ssh-ed25519 ${Base64.getEncoder().encodeToString(first)}\n# comment\n[h]:2222 ssh-rsa ${Base64.getEncoder().encodeToString(second)}\n")
            val keys=KnownHostKeys.list(file)
            assertEquals(listOf("ssh-ed25519","ssh-rsa"),keys.map {it.type})
            assertEquals(KnownHostKeys.fingerprint(first),keys[0].fingerprint)
            assertTrue(keys[0].fingerprint.startsWith("SHA256:") && !keys[0].fingerprint.endsWith("="))
            assertTrue(KnownHostKeys.forget(file,keys[0].fingerprint))
            assertEquals(listOf("ssh-rsa"),KnownHostKeys.list(file).map {it.type})
            assertTrue(file.readText().contains("# comment"))
            assertFalse(KnownHostKeys.forget(file,keys[0].fingerprint))
        } finally {file.delete()}
    }
    @Test fun readsKeyTypeFromBlob() {
        assertEquals("ssh-ed25519",KnownHostKeys.keyType(blob("ssh-ed25519","x")))
        assertEquals("unknown",KnownHostKeys.keyType(byteArrayOf(1,2)))
    }
}

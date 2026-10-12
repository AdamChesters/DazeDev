package com.adamchesters.dazedev.mobile.voice
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
class ModelFileIntegrityTest {
    @Test fun rejectsTruncatedOrChangedCachedModelAndHonoursCancellation() {
        val file=Files.createTempFile("dazedev-model-", ".fixture").toFile()
        try {
            val content=ByteArray(131072){it.toByte()};file.writeBytes(content)
            val hash=MessageDigest.getInstance("SHA-256").digest(content).joinToString(""){"%02x".format(it)}
            assertTrue(ModelFileIntegrity.matches(file,content.size.toLong(),hash))
            assertFalse(ModelFileIntegrity.matches(file,content.size.toLong()+1,hash))
            assertFalse(ModelFileIntegrity.matches(file,content.size.toLong(),"0".repeat(64)))
            assertThrows(InterruptedException::class.java){ModelFileIntegrity.matches(file,content.size.toLong(),hash){throw InterruptedException()}}
            assertEquals(content.size.toLong(),file.length())
        } finally {file.delete()}
    }
}

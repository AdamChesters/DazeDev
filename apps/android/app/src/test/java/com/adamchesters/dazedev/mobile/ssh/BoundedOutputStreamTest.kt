package com.adamchesters.dazedev.mobile.ssh

import org.junit.Assert.*
import org.junit.Test

class BoundedOutputStreamTest {
    @Test
    fun `caps single byte and chunked remote output`() {
        val output = BoundedOutputStream(5)
        output.write("abc".toByteArray())
        assertFalse(output.exceeded)
        output.write("defgh".toByteArray(), 0, 5)
        output.write(120)
        assertTrue(output.exceeded)
        assertEquals("abcde", output.text())
    }

    @Test
    fun `preserves output exactly at the limit`() {
        val output = BoundedOutputStream(3)
        output.write("abc".toByteArray())
        assertFalse(output.exceeded)
        assertEquals("abc", output.text())
    }
}

package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptAccumulatorTest {
    @Test
    fun `completed lines remain while the next line is transcribed`() {
        val transcript = TranscriptAccumulator()

        assertEquals("run", transcript.partial("run"))
        assertEquals("run tests", transcript.partial("run tests"))
        assertEquals("run tests", transcript.complete("run tests"))
        assertEquals("run tests and", transcript.partial("and"))
        assertEquals("run tests and publish", transcript.partial("and publish"))
        assertEquals("run tests and publish", transcript.complete("and publish"))
    }

    @Test
    fun `reset clears completed and partial text`() {
        val transcript = TranscriptAccumulator()

        transcript.complete("old line")
        transcript.partial("old partial")
        transcript.reset()

        assertEquals("new", transcript.partial("new"))
    }

    @Test
    fun `blank callbacks do not add spaces or lines`() {
        val transcript = TranscriptAccumulator()

        assertEquals("", transcript.partial("   "))
        assertEquals("", transcript.complete("   "))
        assertEquals("hello", transcript.complete("  hello  "))
        assertEquals("hello", transcript.partial(""))
    }

    @Test fun reportsPendingWordsUntilTheLineCompletes() {
        val t = TranscriptAccumulator()
        assertFalse(t.hasPartial())
        t.partial("hello wor")
        assertTrue(t.hasPartial())
        t.complete("hello world")
        assertFalse(t.hasPartial())
    }
}

package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BroadcastTest {
    private val running = mapOf("h1" to listOf("$1" to "s1", "$2" to "s2"), "h2" to listOf("$1" to "t1"))

    private fun candidate(
        label: String,
        host: String?,
        fingerprint: String,
        id: String,
        stamp: String,
    ) = BroadcastCandidate(label, host, fingerprint, id, stamp)

    @Test fun recipientsAreVerifiedAndEachSessionIsSentOnce() {
        val snapshot =
            Broadcast.snapshot(
                listOf(
                    candidate("open", "h1", "m1:u:s", "$1", "s1"),
                    candidate("row of the open terminal", "h1", "m1:u:s", "$1", "s1"),
                    // The same machine saved twice on this phone is still one session.
                    candidate("same machine, second entry", "h3", "m1:u:s", "$1", "s1"),
                    candidate("other", "h1", "m1:u:s", "$2", "s2"),
                    candidate("restarted", "h1", "m1:u:s", "$2", "old"),
                    candidate("offline", "h9", "m9:u:s", "$1", "x"),
                    candidate("not set up", null, "", "$1", "x"),
                    candidate("second host", "h2", "m2:u:s", "$1", "t1"),
                ),
            ) { running[it] ?: if (it == "h3") running["h1"] else null }
        assertEquals(listOf("open", "other", "second host"), snapshot.recipients.map { it.label })
        assertEquals(listOf("restarted", "offline", "not set up"), snapshot.skipped)
        assertEquals(2, snapshot.mirrors)
    }

    @Test fun theRevisionChangesWithTheRecipients() {
        val one = Broadcast.snapshot(listOf(candidate("a", "h1", "m1", "$1", "s1"))) { running[it] }
        val same = Broadcast.snapshot(listOf(candidate("a renamed", "h1", "m1", "$1", "s1"))) { running[it] }
        val more = Broadcast.snapshot(listOf(candidate("a", "h1", "m1", "$1", "s1"), candidate("b", "h1", "m1", "$2", "s2"))) { running[it] }
        assertEquals(one.revision, same.revision)
        assertNotEquals(one.revision, more.revision)
    }

    @Test fun textIsCheckedForControlCharacters() {
        assertNull(Broadcast.problem("run tests\n\tthen report"))
        assertTrue(Broadcast.problem("   ")!!.contains("Enter text"))
        assertTrue(Broadcast.problem("bad\u001b[2J")!!.contains("control"))
        assertTrue(Broadcast.problem("x".repeat(Broadcast.MAX_TEXT + 1))!!.contains("16 KiB"))
    }

    @Test fun aDeliverySendsOnceAndReportsFailuresWithoutRetrying() =
        runBlocking {
            val snapshot = Broadcast.snapshot(listOf(candidate("a", "h1", "m1", "$1", "s1"), candidate("b", "h1", "m1", "$2", "s2"))) { running[it] }
            val attempts = mutableListOf<String>()
            val delivery = Broadcast.Delivery(snapshot, "hello")
            val result =
                delivery.send { recipient, text ->
                    synchronized(attempts) { attempts.add("${recipient.label}:$text") }
                    if (recipient.label == "b") error("Host disconnected.")
                }
            assertEquals(listOf("a"), result.delivered)
            assertEquals(listOf("b: Host disconnected."), result.failed)
            assertEquals(2, attempts.size)
            try {
                delivery.send { _, _ -> fail("A second send must not deliver") }
                fail("Expected the second send to be refused")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("already been submitted"))
            }
            assertEquals(2, attempts.size)
        }

    @Test fun commandsQuoteEverythingAndCarryTheTextAsBase64() {
        val recipient = BroadcastRecipient("a", "h1", "$3", "1:2:3", "id")
        val command = Broadcast.command("my sock", recipient, "it's \$HOME `x`")
        assertTrue(command.startsWith("exec python3 -I -c '"))
        assertTrue(command.contains(" 'my sock' '\$3' '1:2:3' "))
        assertTrue(!command.contains("\$HOME") && !command.contains("`x`"))
        try {
            Broadcast.command("s", recipient.copy(sessionId = "main; rm -rf ~"), "x")
            fail("Expected an invalid session id to be refused")
        } catch (_: IllegalArgumentException) {
        }
    }
}

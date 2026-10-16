package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MessageFramesTest {
    // Same layout as frame() in host.py: u32 length, u8 flags, u16 id length, id, data.
    private fun frame(
        id: String,
        data: ByteArray,
        last: Boolean,
    ): ByteArray {
        val ident = id.toByteArray()
        val body = byteArrayOf(if (last) 1 else 0, (ident.size shr 8).toByte(), ident.size.toByte()) + ident + data
        val n = body.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + body
    }

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test fun decodesAKnownByteFixture() {
        // {"id":"7","result":1} framed by frame() in host.py.
        val hex = "00000019010001377b226964223a2237222c22726573756c74223a317d"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(listOf("""{"id":"7","result":1}"""), MessageFrames().push(bytes).map(::text))
    }

    @Test fun joinsInterleavedPartsPerId() {
        val big = """{"id":"1","result":"${"界".repeat(5000)}"}""".toByteArray()
        val ping = """{"id":"2","result":"pong"}""".toByteArray()
        val half = big.size / 2
        val stream =
            frame("1", big.copyOfRange(0, half), false) +
                frame("2", ping, true) +
                frame("1", big.copyOfRange(half, big.size), true)
        val frames = MessageFrames()
        val out = mutableListOf<String>()
        // One byte at a time covers headers and UTF-8 split across reads.
        for (b in stream) frames.push(byteArrayOf(b)).forEach { out.add(text(it)) }
        assertEquals(listOf(text(ping), text(big)), out)
    }

    @Test fun eventsUseAnEmptyId() {
        val event = """{"event":"browser-open","id":"b1","url":"https://example.com"}""".toByteArray()
        val messages = MessageFrames().push(frame("", event, true) + frame("3", "{}".toByteArray(), true))
        assertEquals(2, messages.size)
        assertEquals("browser-open", JSONObject(text(messages[0])).getString("event"))
    }

    @Test fun readsOnlyTheGivenRange() {
        val bytes = byteArrayOf(9, 9) + frame("1", "{}".toByteArray(), true) + byteArrayOf(9)
        assertEquals(1, MessageFrames().push(bytes, 2, bytes.size - 3).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesATooShortFrame() {
        MessageFrames().push(byteArrayOf(0, 0, 0, 2, 1, 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnIdLongerThanTheFrame() {
        MessageFrames().push(byteArrayOf(0, 0, 0, 3, 1, 0, 9))
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesMessagesOverTheLimit() {
        val frames = MessageFrames(maxMessage = 8)
        frames.push(frame("1", ByteArray(6), false))
        frames.push(frame("1", ByteArray(6), true))
    }

    @Test fun requestsAreLengthPrefixed() {
        val json = """{"id":"1","method":"ping","args":{}}""".toByteArray()
        val framed = MessageFrames.request(json)
        assertArrayEquals(byteArrayOf(0, 0, 0, json.size.toByte()), framed.copyOfRange(0, 4))
        assertArrayEquals(json, framed.copyOfRange(4, framed.size))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), MessageFrames.request(ByteArray(0x010203)).copyOfRange(0, 4))
    }

    @Test fun errorsKeepTheTextTheAppMatches() {
        assertEquals("CONFLICT: file changed", MessageFrames.errorText(JSONObject().put("code", "conflict").put("message", "file changed")))
        assertEquals(
            "Session ended or restarted; reconnect first",
            MessageFrames.errorText(JSONObject().put("code", "failed").put("message", "Session ended or restarted; reconnect first")),
        )
        assertEquals("old text", MessageFrames.errorText("old text"))
        assertNull(MessageFrames.errorText(null))
        assertNull(MessageFrames.errorText(JSONObject.NULL))
    }
}

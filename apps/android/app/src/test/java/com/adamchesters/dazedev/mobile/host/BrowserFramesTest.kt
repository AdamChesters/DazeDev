package com.adamchesters.dazedev.mobile.host

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserFramesTest {
    private fun frame() = JSONObject().put("event", "browser-open").put("id", "a".repeat(32)).put("url", "https://example.com/login")

    @Test fun readsBrowserEvent() {
        assertEquals("https://example.com/login", BrowserFrames.parse(frame())?.url)
    }

    @Test fun rejectsRequestReply() {
        assertNull(BrowserFrames.parse(frame().removeEvent()))
    }

    @Test fun rejectsInvalidIdentity() {
        assertNull(BrowserFrames.parse(frame().put("id", "ready")))
    }

    @Test fun rejectsNonStringUrl() {
        assertNull(BrowserFrames.parse(frame().put("url", JSONObject())))
    }

    @Test fun rejectsOversizeUrl() {
        assertNull(BrowserFrames.parse(frame().put("url", "x".repeat(8193))))
    }

    @Test fun rejectsControlCharacters() {
        assertNull(BrowserFrames.parse(frame().put("url", "https://example.com/\n")))
    }

    private fun JSONObject.removeEvent(): JSONObject {
        remove("event")
        return this
    }
}

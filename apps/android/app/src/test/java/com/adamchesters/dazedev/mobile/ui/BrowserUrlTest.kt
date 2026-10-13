package com.adamchesters.dazedev.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserUrlTest {
    @Test fun acceptsWebLinksAndTailnetAddresses() {
        for(url in listOf("https://example.com/login?code=abc", "http://100.64.0.1:3000/preview")) {
            assertEquals(url,BrowserUrl.validate(url))
        }
    }
    @Test fun rejectsCommandsCredentialsAndRemoteLoopback() {
        for(url in listOf("file:///etc/passwd", "javascript:alert(1)", "https://user:secret@example.com/",
            "http://localhost:1234/", "http://127.0.0.1/", "http://[::1]/", "https://example.com/\nnext", "https://example.com:99999/")) {
            var failed=false
            try {BrowserUrl.validate(url)}catch(_:Exception){failed=true}
            check(failed){"Accepted unsafe URL: $url"}
        }
    }
}

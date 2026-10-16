package com.adamchesters.dazedev.mobile

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM tests must use Android's org.json, not the reference library, so JSON output matches the phone. */
class AndroidJsonTest {
    @Test fun usesAndroidsJsonImplementation() {
        // Android escapes every "/"; the reference library does not.
        assertEquals("""{"path":"a\/b"}""", JSONObject().put("path", "a/b").toString())
        // Android's getString converts numbers, as on the phone.
        assertEquals("1", JSONObject("""{"n":1}""").getString("n"))
    }
}

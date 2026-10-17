package com.adamchesters.dazedev.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class TerminalFindTest {
    private val lines = TerminalLines.parse("""{"first":[0,1,3,4],"text":["$ make","error: one   error: two","ok","Error: three"]}""")

    @Test fun findStartsAtTheLatestMatchAndWraps() {
        val find = TerminalFind.search(lines, SearchOptions("error"))
        assertEquals(3, find.size)
        assertEquals(LineMatch(3, 0, 5), find.selected())
        // The buffer row of the logical line, and the text it must still have when revealed.
        assertEquals(4 to "Error: three", find.place(find.selected()!!))
        assertEquals(LineMatch(1, 0, 5), find.next())
        assertEquals(LineMatch(3, 0, 5), find.previous())
        assertEquals(LineMatch(1, 13, 18), find.previous())
        assertEquals(1, TerminalFind.search(lines, SearchOptions("Error", matchCase = true)).size)
        assertEquals(2, TerminalFind.search(lines, SearchOptions("error: (one|two)", regex = true)).size)
    }

    @Test fun noMatchesIsEmpty() {
        val find = TerminalFind.search(lines, SearchOptions("missing"))
        assertEquals(0, find.size)
        assertNull(find.next())
        assertNull(find.selected())
    }

    @Test fun malformedBuffersAreRefused() {
        for (bad in listOf("""{"first":[0],"text":[]}""", """{"text":[]}""", "[]")) {
            try {
                TerminalLines.parse(bad)
                fail("Expected $bad to be refused")
            } catch (_: Exception) {
            }
        }
    }
}

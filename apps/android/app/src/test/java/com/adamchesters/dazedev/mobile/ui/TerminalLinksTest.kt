package com.adamchesters.dazedev.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLinksTest {
    private fun at(
        text: String,
        tapped: String,
    ) = TerminalLinks.at(text, text.indexOf(tapped))

    @Test fun findsWebAddressesWithoutTrailingPunctuation() {
        val text = "See https://example.com/docs?a=1, then (https://github.com/x/y)."
        assertEquals(listOf("https://example.com/docs?a=1", "https://github.com/x/y"), TerminalLinks.urls(text).map { it.text })
        assertEquals(TerminalTarget.Kind.Url, at(text, "example")?.kind)
        assertNull(at(text, "then"))
        assertTrue(TerminalLinks.urls("ftp://example.com javascript:alert(1)").isEmpty())
    }

    @Test fun findsPathsLikeTheDesktop() {
        assertEquals(listOf("src/main.rs", "/etc/hosts.conf", "~/notes/todo.md"), TerminalLinks.paths("edited src/main.rs and /etc/hosts.conf, ~/notes/todo.md").map { it.text })
        assertEquals(listOf("docs/My File.md"), TerminalLinks.paths("see [guide](docs/My File.md) now").map { it.text })
        assertEquals(listOf("with space.txt"), TerminalLinks.paths("wrote \"with space.txt\"").map { it.text })
        assertEquals(listOf("README.md"), TerminalLinks.paths("open README.md").map { it.text })
        assertEquals(listOf("apps/repo/Dockerfile"), TerminalLinks.paths("built apps/repo/Dockerfile").map { it.text })
        // Not paths: unknown extensions, URLs, other schemes and words glued to the path.
        assertTrue(TerminalLinks.paths("binary.exe archive.tar").isEmpty())
        assertTrue(TerminalLinks.paths("https://example.com/readme.md").isEmpty())
        assertTrue(TerminalLinks.paths("file:///etc/x.txt").isEmpty())
        assertTrue(TerminalLinks.paths("abc:/x.txt").isEmpty())
    }

    @Test fun aTapOnlyMatchesTheLinkUnderIt() {
        val text = "Saved screenshot to ./out/shot.png at https://ci.example/run/5"
        val path = at(text, "shot.png")!!
        assertEquals(TerminalTarget.Kind.Path, path.kind)
        assertEquals("./out/shot.png", path.text)
        assertEquals(TerminalTarget.Kind.Url, at(text, "ci.example")?.kind)
        assertNull(TerminalLinks.at(text, text.indexOf(" at ")))
        assertNull(TerminalLinks.at(text, -1))
        assertNull(TerminalLinks.at(text, text.length))
        assertNull(TerminalLinks.at("x".repeat(TerminalLinks.MAX_TEXT + 1), 1))
    }

    @Test fun previewKindsFollowTheExtension() {
        assertEquals(PreviewKind.Image, TerminalLinks.previewKind("a/b.PNG"))
        assertEquals(PreviewKind.Image, TerminalLinks.previewKind("shot.jpeg?x=1"))
        assertEquals(PreviewKind.Markdown, TerminalLinks.previewKind("AGENTS.md"))
        assertEquals(PreviewKind.Markdown, TerminalLinks.previewKind("repo/.cursorrules"))
        assertEquals(PreviewKind.Html, TerminalLinks.previewKind("index.htm"))
        assertEquals(PreviewKind.Text, TerminalLinks.previewKind("main.kt"))
    }
}

class TextSearchTest {
    private val lines = listOf("Alpha beta", "gamma ALPHA", "", "alphabet")

    @Test fun plainSearchIgnoresCaseUnlessAsked() {
        assertEquals(listOf(LineMatch(0, 0, 5), LineMatch(1, 6, 11), LineMatch(3, 0, 5)), TextSearch.find(lines, SearchOptions("alpha")))
        assertEquals(listOf(LineMatch(3, 0, 5)), TextSearch.find(lines, SearchOptions("alpha", matchCase = true)))
        // Without Regular expression the query is literal.
        assertTrue(TextSearch.find(lines, SearchOptions("a.p")).isEmpty())
    }

    @Test fun regularExpressionsSkipEmptyMatchesAndRespectTheLimit() {
        assertEquals(listOf(LineMatch(0, 0, 5), LineMatch(3, 0, 8)), TextSearch.find(lines, SearchOptions("^Alpha\\w*", regex = true)).filter { it.line != 1 })
        assertTrue(TextSearch.find(lines, SearchOptions("x*", regex = true)).isEmpty())
        assertEquals(2, TextSearch.find(lines, SearchOptions("a"), limit = 2).size)
        assertTrue(TextSearch.find(lines, SearchOptions("")).isEmpty())
    }

    @Test fun badPatternsAreReportedNotThrownAsCrashes() {
        try {
            TextSearch.find(lines, SearchOptions("(", regex = true))
            throw AssertionError("Expected a pattern error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("Invalid regular expression"))
        }
    }

    @Test fun catastrophicPatternsTimeOut() {
        val started = System.nanoTime()
        try {
            TextSearch.find(listOf("a".repeat(40)), SearchOptions("(.*a){12}x", regex = true), timeoutMs = 100)
            throw AssertionError("Expected a timeout")
        } catch (_: SearchTimeoutException) {
        }
        assertFalse((System.nanoTime() - started) / 1_000_000 > 5_000)
    }
}

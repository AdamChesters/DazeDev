package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.HostFiles
import com.adamchesters.dazedev.mobile.host.HostImage
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class InertHtmlTest {
    @Test fun markdownIsEscapedAndRemoteImagesAreNotLoaded() {
        val page = InertHtml.markdown("# Title <b>\n**bold** `code`\n![x](https://example.com/a.png)\n![y](data:image/png;base64,AAAA)\n```\n<script>alert(1)</script>\n```")
        assertTrue(page.html.contains("<h1>Title &lt;b&gt;</h1>"))
        assertTrue(page.html.contains("<strong>bold</strong> <code>code</code>"))
        assertTrue(page.html.contains("<img src=\"data:image/png;base64,AAAA\""))
        assertFalse(page.html.contains("<img src=\"https"))
        assertTrue(page.html.contains("&lt;script&gt;"))
        assertFalse(page.html.contains("<script>"))
        assertEquals(listOf("https://example.com/a.png"), page.remoteImages)
        assertTrue(page.html.contains("Content-Security-Policy"))
    }

    @Test fun htmlKeepsStaticMarkupAndRemovesActiveParts() {
        val page =
            InertHtml.html(
                """<html><head><meta http-equiv="refresh" content="0;url=https://evil.example"><base href="https://evil.example/"><link rel="stylesheet" href="https://x/y.css"><script>alert(1)</script></head>
                <body onload="alert(2)"><h1 style="color:red">Hi</h1><a href="https://example.com" target="_blank">link</a>
                <img src="https://example.com/cat.png" srcset="https://example.com/2x.png 2x"><img src="data:image/png;base64,AAAA">
                <form action="https://evil.example"><input name="password"><button>Go</button></form>
                <iframe src="https://example.com"></iframe><div style="background:url(https://x/y.png)">bg</div>
                <svg><script>alert(3)</script></svg><style>@import url(https://x/z.css); p{color:blue}</style><!-- <script>no</script> --></body></html>""",
            )
        val body = page.html.substringAfter("</head><body>")
        assertTrue(body.contains("<h1 style=\"color:red\">Hi</h1>"))
        assertTrue(body.contains("link"))
        for (bad in listOf("<script", "onload", "href=", "srcset", "<form", "<input", "<button", "<iframe", "refresh", "<base", "<link", "url(", "@import", "evil.example", "src=\"https")) {
            assertFalse("$bad survived: $body", body.contains(bad, ignoreCase = true))
        }
        assertTrue(body.contains("src=\"data:image/png;base64,AAAA\""))
        assertTrue(body.contains("p{color:blue}"))
        assertEquals(listOf("https://example.com/cat.png"), page.remoteImages)
    }
}

class HostFilesTest {
    private fun chunk(
        offset: Int,
        bytes: ByteArray,
        size: Int,
        revision: String = "r1",
        mime: String = "image/png",
    ) = JSONObject()
        .put("path", "/home/u/a.png")
        .put("mime", mime)
        .put("revision", revision)
        .put("size", size)
        .put("offset", offset)
        .put("data", Base64.getEncoder().encodeToString(bytes))

    @Test fun imagesAreReadInCheckedChunks() =
        runBlocking {
            val image = ByteArray(HostFiles.IMAGE_CHUNK * 2 + 10) { it.toByte() }
            val seen = mutableListOf<JSONObject>()
            val files =
                HostFiles { method, args, _ ->
                    assertEquals("image_read", method)
                    seen.add(args)
                    val offset = args.getInt("offset")
                    chunk(offset, image.copyOfRange(offset, minOf(image.size, offset + HostFiles.IMAGE_CHUNK)), image.size)
                }
            val read: HostImage = files.image("a.png", "/home/u")
            assertArrayEquals(image, read.bytes)
            assertEquals(3, seen.size)
            assertEquals("/home/u", seen[0].getString("directory"))
            assertFalse(seen[0].has("revision"))
            assertEquals("r1", seen[1].getString("revision"))
        }

    @Test fun inconsistentImageChunksAreRefused() {
        val bytes = ByteArray(HostFiles.IMAGE_CHUNK)
        val cases =
            listOf(
                chunk(0, bytes, HostFiles.MAX_IMAGE + 1),
                chunk(0, bytes, 100_000, mime = "text/html"),
                chunk(1, bytes, 100_000),
                chunk(0, ByteArray(10), 100_000),
                chunk(0, bytes, 100_000, revision = ""),
            )
        for (case in cases) {
            try {
                HostFiles.checkChunk(case, 0, "", 0, "")
                fail("Expected $case to be refused")
            } catch (_: IllegalStateException) {
            }
        }
        // A later chunk must match the first chunk's revision, size and type.
        try {
            HostFiles.checkChunk(chunk(HostFiles.IMAGE_CHUNK, bytes, 100_000, revision = "r2"), HostFiles.IMAGE_CHUNK, "r1", 100_000, "image/png")
            fail("Expected a changed image to be refused")
        } catch (_: IllegalStateException) {
        }
    }

    @Test fun textLargerThanTheHelperLimitIsRefused() =
        runBlocking {
            val files = HostFiles { _, _, _ -> JSONObject().put("path", "/a.txt").put("text", "x".repeat(HostFiles.MAX_TEXT + 1)) }
            try {
                files.text("a.txt", null)
                fail("Expected the size limit")
            } catch (_: IllegalStateException) {
            }
            val small = HostFiles { _, args, _ -> JSONObject().put("path", "/w/" + args.getString("path")).put("text", "hello").also { assertFalse(args.has("directory")) } }
            assertEquals("hello", small.text("a.txt", "").text)
        }

    @Test fun imagesDecodeWithinBounds() {
        assertEquals(1, ImageBounds.sampleSize(4000, 3000))
        assertEquals(2, ImageBounds.sampleSize(8000, 1000))
        assertEquals(2, ImageBounds.sampleSize(4096, 4096))
        assertEquals(8, ImageBounds.sampleSize(30_000, 30_000))
    }
}

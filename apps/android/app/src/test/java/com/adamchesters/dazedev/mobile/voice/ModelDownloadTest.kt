package com.adamchesters.dazedev.mobile.voice

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files

class ModelDownloadTest {
    private val content = ByteArray(200_000) { (it * 7).toByte() }

    /** A one-file HTTP server on loopback; the test classpath has no JDK HTTP server. */
    private fun serve(
        ranges: Boolean,
        block: (URL, MutableList<String?>) -> Unit,
    ) {
        val seen = java.util.Collections.synchronizedList(mutableListOf<String?>())
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val thread =
            Thread {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use {
                        val reader = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var range: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.lowercase().startsWith("range:")) range = line.substringAfter(':').trim()
                        }
                        seen.add(range)
                        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toIntOrNull()
                        val out = it.getOutputStream()
                        if (ranges && start != null) {
                            out.write("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $start-${content.size - 1}/${content.size}\r\nContent-Length: ${content.size - start}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(content, start, content.size - start)
                        } else {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(content)
                        }
                        out.flush()
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }
        try {
            block(URL("http://127.0.0.1:${server.localPort}/model"), seen)
        } finally {
            server.close()
            thread.join(2000)
        }
    }

    private fun partial(bytes: Int): File = Files.createTempFile("dazedev-model-", ".partial").toFile().also { it.writeBytes(content.copyOf(bytes)) }

    @Test fun resumesWithRangeWhenTheServerSupportsIt() =
        serve(ranges = true) { url, seen ->
            val file = partial(50_000)
            try {
                var last = 0L
                ModelDownload.fetch(url, file, content.size.toLong(), {}) { last = it }
                assertEquals(listOf<String?>("bytes=50000-"), seen)
                assertArrayEquals(content, file.readBytes())
                assertEquals(content.size.toLong(), last)
            } finally {
                file.delete()
            }
        }

    @Test fun restartsWhenTheServerIgnoresRange() =
        serve(ranges = false) { url, _ ->
            val file = partial(50_000)
            try {
                ModelDownload.fetch(url, file, content.size.toLong(), {})
                assertArrayEquals(content, file.readBytes())
            } finally {
                file.delete()
            }
        }

    @Test fun rejectsMoreBytesThanPinned() =
        serve(ranges = false) { url, _ ->
            val file = Files.createTempFile("dazedev-model-", ".partial").toFile()
            try {
                assertThrows(IllegalStateException::class.java) { ModelDownload.fetch(url, file, 1000, {}) }
                assertFalse(file.exists())
            } finally {
                file.delete()
            }
        }

    @Test fun verifiedMarkerSkipsRehashUntilTheFileChanges() {
        val dir = Files.createTempDirectory("dazedev-marker").toFile()
        try {
            val model = File(dir, "model.ort")
            model.writeBytes(content)
            val marker = VerifiedMarker(File(dir, "verified.json"))
            assertFalse(marker.matches(model, content.size.toLong(), "abc"))
            marker.record(model, "abc")
            assertTrue(marker.matches(model, content.size.toLong(), "abc"))
            assertFalse(marker.matches(model, content.size.toLong(), "other"))
            model.setLastModified(model.lastModified() - 60_000)
            assertFalse(marker.matches(model, content.size.toLong(), "abc"))
        } finally {
            dir.deleteRecursively()
        }
    }
}

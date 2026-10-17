package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.Collections
import kotlin.random.Random

/** A helper that accepts uploads like host.py: ranges are written once, in any order over binary frames. */
private class FakeUploadHost(
    val parallel: Int? = 4,
    val chunk: Int = 1_048_576,
    val available: Long = 1L shl 40,
) {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    var data = ByteArray(0)
    var size = 0L
    var received = 0L
    var inFlight = 0
    var maxInFlight = 0
    var aborted = false
    var finished = false
    var published: String? = null
    val ranges = mutableListOf<LongRange>()

    val request: HostRequest = { method, args, _ ->
        calls.add(method)
        when (method) {
            "upload_preflight" -> {
                val size = args.getLong("size")
                JSONObject()
                    .put("directory", if (args.optBoolean("paste")) "/home/u/.local/share/dazedev/uploads" else "/home/u/project")
                    .put("freeBytes", available)
                    .put("availableBytes", available)
                    .put("reservedBytes", 0)
                    .put("requiredBytes", size + 16L * 1024 * 1024)
                    .put("maxBytes", if (args.optBoolean("paste")) 10L * 1024 * 1024 else 256L * 1024 * 1024)
            }

            "upload_begin" -> {
                size = args.getLong("size")
                data = ByteArray(size.toInt())
                JSONObject()
                    .put("id", "t1")
                    .put("path", "/home/u/project/${args.getString("name")}")
                    .put("chunk", chunk)
                    .also { if (parallel != null) it.put("parallel", parallel) }
            }

            "upload_chunk" -> {
                synchronized(this) {
                    inFlight++
                    maxInFlight = maxOf(maxInFlight, inFlight)
                }
                delay(Random.nextLong(0, 3))
                val bytes = Base64.getDecoder().decode(args.getString("data"))
                val offset = args.getLong("offset")
                require(bytes.size <= HostUpload.MAX_CHUNK) { "chunk too large" }
                synchronized(this) {
                    val range = offset until offset + bytes.size
                    require(ranges.none { it.first < range.last + 1 && range.first < it.last + 1 }) { "overlap" }
                    ranges.add(range)
                    System.arraycopy(bytes, 0, data, offset.toInt(), bytes.size)
                    received += bytes.size
                    inFlight--
                }
                val contiguous =
                    ranges
                        .sortedBy { it.first }
                        .fold(0L) { end, r -> if (r.first == end) r.last + 1 else end }
                JSONObject().put("offset", contiguous).put("received", received)
            }

            "upload_finish" -> {
                require(received == size) { "Upload incomplete" }
                finished = true
                published = "/home/u/project/file.bin"
                JSONObject().put("path", published).put("size", size)
            }

            "upload_abort" -> {
                if (!finished) aborted = true
                true
            }

            else -> {
                error("unexpected $method")
            }
        }
    }
}

class HostUploadTest {
    private fun source(bytes: ByteArray) = UploadSource("file.bin", bytes.size.toLong()) { bytes.inputStream() }

    @Test fun parallelUploadSendsEveryByteOnceWithinTheLaneAndChunkCaps() =
        runBlocking {
            val host = FakeUploadHost(parallel = 16)
            val bytes = Random(7).nextBytes(3 * 512 * 1024 + 1234)
            val progress = mutableListOf<Long>()
            val path = HostUpload(host.request).upload(source(bytes), "~/project", progress = { progress.add(it) })
            assertEquals("/home/u/project/file.bin", path)
            assertArrayEquals(bytes, host.data)
            assertTrue("never more than ${HostUpload.MAX_LANES} chunks in flight", host.maxInFlight <= HostUpload.MAX_LANES)
            assertEquals(bytes.size.toLong(), progress.last())
            assertEquals(progress.sorted(), progress)
            assertFalse(host.aborted)
            // The cleanup call after finish is harmless on the helper.
            assertEquals("upload_abort", host.calls.last())
        }

    @Test fun olderHostsTakeOneChunkAtATimeInOrder() =
        runBlocking {
            val host = FakeUploadHost(parallel = null, chunk = 32_768)
            val bytes = Random(3).nextBytes(100_000)
            HostUpload(host.request).upload(source(bytes), "/tmp")
            assertArrayEquals(bytes, host.data)
            assertEquals(1, host.maxInFlight)
            assertEquals(4, host.calls.count { it == "upload_chunk" })
        }

    @Test fun emptyFilesFinishWithoutChunks() =
        runBlocking {
            val host = FakeUploadHost()
            HostUpload(host.request).upload(source(ByteArray(0)), "/tmp")
            assertEquals(0, host.calls.count { it == "upload_chunk" })
            assertTrue(host.finished)
        }

    @Test fun cancelStopsSendingAndAbortsTheTransfer() =
        runBlocking {
            val host = FakeUploadHost(chunk = 65_536)
            val bytes = ByteArray(2_000_000)
            var chunks = 0
            try {
                HostUpload(host.request).upload(source(bytes), "/tmp", current = { if (++chunks > 6) throw UploadCancelledException() })
                fail("Expected cancellation")
            } catch (_: UploadCancelledException) {
            }
            assertTrue(host.aborted)
            assertFalse(host.finished)
            assertTrue(host.calls.count { it == "upload_chunk" } < 30)
        }

    @Test fun aSourceThatChangesSizeIsRefused() =
        runBlocking {
            val host = FakeUploadHost()
            val short = UploadSource("file.bin", 100_000) { ByteArray(90_000).inputStream() }
            try {
                HostUpload(host.request).upload(short, "/tmp")
                fail("Expected a changed-file error")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("changed"))
            }
            assertTrue(host.aborted)
            val long = UploadSource("file.bin", 1000) { ByteArray(2000).inputStream() }
            try {
                HostUpload(FakeUploadHost().request).upload(long, "/tmp")
                fail("Expected a changed-file error")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("changed"))
            }
        }

    @Test fun limitsNamesAndStorageAreCheckedBeforeAnyTransfer() =
        runBlocking {
            val host = FakeUploadHost(available = 1_000_000)
            for (name in listOf("", ".", "..", "a/b", "a\\b", "bad\nname")) {
                try {
                    HostUpload(host.request).upload(UploadSource(name, 1) { ByteArray(1).inputStream() }, "/tmp")
                    fail("Expected $name to be refused")
                } catch (_: IllegalArgumentException) {
                }
            }
            try {
                HostUpload(host.request).upload(UploadSource("big", HostUpload.MAX_PASTE + 1) { ByteArray(0).inputStream() }, null, paste = true)
                fail("Expected the quick paste limit")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("10 MiB"))
            }
            try {
                HostUpload(host.request).upload(UploadSource("fits", 10) { ByteArray(10).inputStream() }, "/tmp")
                fail("Expected the headroom check")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("storage"))
            }
            assertFalse(host.calls.contains("upload_begin"))
        }

    @Test fun capacityRepliesAreValidated() {
        val good =
            JSONObject()
                .put("directory", "/d")
                .put("freeBytes", 10)
                .put("availableBytes", 10)
                .put("reservedBytes", 0)
                .put("requiredBytes", 5)
                .put("maxBytes", 100)
        val capacity = HostUpload.capacity(good, 5)
        assertTrue(capacity.fits(10))
        assertFalse(capacity.fits(11))
        for (broken in listOf(JSONObject(good.toString()).put("freeBytes", -1), JSONObject(good.toString()).put("maxBytes", 1.5), JSONObject(good.toString()).put("directory", ""), JSONObject(good.toString()).put("requiredBytes", 1))) {
            try {
                HostUpload.capacity(broken, 5)
                fail("Expected $broken to be refused")
            } catch (_: IllegalStateException) {
            }
        }
    }

    @Test fun chunkSizeFollowsTheHostWithinTheLinkLimit() {
        assertEquals(32_768, HostUpload.chunkSize(JSONObject()))
        assertEquals(32_768, HostUpload.chunkSize(JSONObject().put("chunk", 100)))
        assertEquals(65_536, HostUpload.chunkSize(JSONObject().put("chunk", 65_536)))
        assertEquals(HostUpload.MAX_CHUNK, HostUpload.chunkSize(JSONObject().put("chunk", 1_048_576)))
        // A full chunk, base64 encoded with its request envelope, fits the link's 1 MiB request limit.
        val request = JSONObject().put("id", "1").put("method", "upload_chunk").put("args", JSONObject().put("id", "a".repeat(32)).put("offset", Long.MAX_VALUE).put("data", Base64.getEncoder().encodeToString(ByteArray(HostUpload.MAX_CHUNK))))
        assertTrue(request.toString().toByteArray().size <= 1_048_576)
    }

    @Test fun sharedNamesAreCleaned() {
        assertEquals("report.pdf", HostUpload.cleanName("report.pdf", "application/pdf"))
        assertEquals("a_b.txt", HostUpload.cleanName("a/b.txt", "text/plain"))
        assertEquals("screenshot.png", HostUpload.cleanName(null, "image/png"))
        assertEquals("shared.bin", HostUpload.cleanName("..", null))
        assertEquals("1.00 MiB", HostUpload.bytesLabel(1_048_576))
    }
}

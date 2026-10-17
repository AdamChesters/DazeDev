package com.adamchesters.dazedev.mobile.voice

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

// The same cases as the desktop's tests/dictionary.test.ts, so both apps treat a shared file alike.
class VoiceDictionaryTest {
    private fun base() = VoiceDictionary.edit(Dictionary(), "a", "project", "ExampleProject", listOf("sample project", "example project"))

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("Expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun matchesEntirePhrasesSpacingAndCaseWithoutCascading() {
        val d = base()
        assertEquals("ExampleProject, ExampleProject. Road closed.", VoiceDictionary.apply("Example project, sample  project. Road closed.", d))
        assertEquals("preexample project and /example project/file", VoiceDictionary.apply("preexample project and /example project/file", d))
        assertEquals("example project.md stays", VoiceDictionary.apply("example project.md stays", d))
        // Applying twice changes nothing more: the preferred spelling maps to itself.
        assertEquals("ExampleProject", VoiceDictionary.apply(VoiceDictionary.apply("example project", d), d))
    }

    @Test fun keepsDisjointOfflineChangesAndIsCommutativeAndIdempotent() {
        val a = VoiceDictionary.edit(base(), "a", "other", "DazeDev", listOf("days dev"))
        val b = VoiceDictionary.edit(base(), "b", "third", "tmux", listOf("tee mux"))
        assertTrue(VoiceDictionary.same(VoiceDictionary.merge(a, b), VoiceDictionary.merge(b, a)))
        assertTrue(VoiceDictionary.same(VoiceDictionary.merge(a, a), a))
        assertEquals(3, VoiceDictionary.merge(a, b).entries.size)
    }

    @Test fun keepsConcurrentEditAndDeleteVisibleAndPausedUntilResolved() {
        val a = VoiceDictionary.edit(base(), "a", "project", "ExampleProject", listOf("example project"), deleted = true)
        val b = VoiceDictionary.edit(base(), "b", "project", "EXAMPLE", listOf("example project"))
        val conflict = VoiceDictionary.merge(a, b)
        assertEquals(1, VoiceDictionary.conflicts(conflict))
        assertEquals("example project", VoiceDictionary.apply("example project", conflict))
        val resolved = VoiceDictionary.edit(conflict, "c", "project", "EXAMPLE", listOf("example project"))
        assertEquals(0, VoiceDictionary.conflicts(VoiceDictionary.merge(resolved, a)))
        assertEquals("EXAMPLE", VoiceDictionary.apply("example project", resolved))
    }

    @Test fun tombstonesPreventAStaleCopyResurrectingDeletions() {
        val old = base()
        val deleted = VoiceDictionary.edit(old, "a", "project", "ExampleProject", listOf("example project"), deleted = true)
        assertTrue(VoiceDictionary.same(VoiceDictionary.merge(old, deleted), deleted))
    }

    @Test fun doesNotChooseBetweenConflictingTargets() {
        val d = VoiceDictionary.edit(base(), "b", "duplicate", "Other", listOf("example project"))
        assertEquals("example project", VoiceDictionary.apply("example project", d))
        assertEquals(listOf("example project"), VoiceDictionary.ambiguous(d))
    }

    @Test fun disabledEntriesAreNotApplied() {
        val disabled = VoiceDictionary.edit(base(), "a", "project", "ExampleProject", listOf("example project"), disabled = true)
        assertEquals("example project", VoiceDictionary.apply("example project", disabled))
    }

    @Test fun rejectsMalformedOversizedAndUntrustedFiles() {
        refused { VoiceDictionary.parse(JSONObject("""{"schema":2,"entries":{}}""")) }
        refused { VoiceDictionary.edit(base(), "a", "project", "X", listOf("bad\nphrase")) }
        refused { VoiceDictionary.parse(JSONObject("""{"schema":1,"entries":{"__proto__":[]}}""")) }
        refused { VoiceDictionary.parse(JSONObject("""{"schema":1,"entries":{"x":[{"clock":{"a":0},"preferred":"p","aliases":["q"],"deleted":false}]}}""")) }
        refused { VoiceDictionary.parse(JSONObject("""{"schema":1,"entries":{"x":[{"clock":{"a":1.5},"preferred":"p","aliases":["q"],"deleted":false}]}}""")) }
        refused { VoiceDictionary.parse(JSONObject("""{"schema":1,"entries":{"x":[{"clock":{"a":1},"preferred":"p","aliases":[],"deleted":false}]}}""")) }
    }

    @Test fun desktopFilesRoundTripIncludingAMissingDisabledField() {
        val desktop = JSONObject("""{"schema":1,"entries":{"k":[{"clock":{"dev-1":2},"preferred":"DazeDev","aliases":["days dev"],"deleted":false}]}}""")
        val parsed = VoiceDictionary.parse(desktop)
        assertNull(parsed.entries.getValue("k")[0].disabled)
        assertFalse(
            VoiceDictionary
                .toJson(parsed)
                .getJSONObject("entries")
                .getJSONArray("k")
                .getJSONObject(0)
                .has("disabled"),
        )
        assertEquals("DazeDev", VoiceDictionary.apply("days dev", parsed))
        val edited = VoiceDictionary.edit(parsed, "phone", "k", "DazeDev", listOf("days dev", "daisy dev"))
        assertEquals(mapOf("dev-1" to 2L, "phone" to 1L), edited.entries.getValue("k")[0].clock)
    }
}

class VoiceDictionaryStoreTest {
    /** A host's dictionary file with host.py's compare-and-swap. */
    private class FakeHost {
        var saved: JSONObject? = null
        var revision = ""
        var writes = 0

        /** Simulates another device writing between our read and write. */
        var interfere = 0

        suspend fun request(
            method: String,
            args: JSONObject,
        ): Any =
            when (method) {
                "dictionary_read" -> {
                    JSONObject().put("dictionary", saved ?: JSONObject.NULL).put("revision", revision)
                }

                "dictionary_write" -> {
                    if (interfere > 0) {
                        interfere--
                        revision += "x"
                    }
                    if (args.getString("revision") != revision) {
                        JSONObject().put("saved", false)
                    } else {
                        writes++
                        saved = JSONObject(args.getJSONObject("dictionary").toString())
                        revision = "r$writes"
                        JSONObject().put("saved", true)
                    }
                }

                else -> {
                    error("unexpected $method")
                }
            }
    }

    private fun folder() = Files.createTempDirectory("dazedev-dictionary-test").toFile()

    @Test fun editsAreSavedLocallyWithABackupAndReload() =
        runBlocking {
            val dir = folder()
            try {
                val file = File(dir, "dictionary.json")
                val store = VoiceDictionaryStore(file) { _, _, _ -> error("offline") }
                store.load()
                store.saveEntry("p", "DazeDev", listOf("days dev"))
                store.saveEntry("q", "tmux", listOf("tee mux"))
                assertTrue(File(dir, "dictionary.json.bak").exists())
                val again = VoiceDictionaryStore(file) { _, _, _ -> error("offline") }
                again.load()
                assertEquals(2, again.dictionary.entries.size)
                assertEquals(store.view.value.state.deviceId, again.view.value.state.deviceId)
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test fun aHostIsMergedOnlyAfterReviewAndSyncRetriesOnAChangedRevision() =
        runBlocking {
            val dir = folder()
            val host = FakeHost()
            host.saved = VoiceDictionary.toJson(VoiceDictionary.edit(Dictionary(), "desktop", "d", "DazeDev", listOf("days dev")))
            host.revision = "r0"
            try {
                val store = VoiceDictionaryStore(File(dir, "dictionary.json")) { _, method, args -> host.request(method, args) }
                store.load()
                store.saveEntry("p", "tmux", listOf("tee mux"))
                store.setHost("h1")
                store.sync()
                // Review first: nothing merged or written yet.
                assertNotNull(store.view.value.adoption)
                assertEquals(1, store.dictionary.entries.size)
                assertEquals(0, host.writes)
                host.interfere = 1
                store.sync(adopt = true)
                assertTrue(store.view.value.state.adopted)
                assertEquals(2, store.dictionary.entries.size)
                assertEquals(1, host.writes)
                assertTrue(VoiceDictionary.same(store.dictionary, VoiceDictionary.parse(host.saved)))
                assertTrue(
                    store.view.value.status
                        .startsWith("Synced"),
                )
                // Up to date: no further write.
                store.sync()
                assertEquals(1, host.writes)
                assertTrue(
                    store.view.value.status
                        .startsWith("Up to date"),
                )
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test fun aStaleEditIsRefusedAndAnOfflineHostKeepsTheLocalCopy() =
        runBlocking {
            val dir = folder()
            try {
                val store = VoiceDictionaryStore(File(dir, "dictionary.json")) { _, _, _ -> error("Connect the sync host first.") }
                store.load()
                store.saveEntry("p", "DazeDev", listOf("days dev"))
                val before = VoiceDictionary.entryRevision(store.dictionary, "p")
                store.saveEntry("p", "DazeDev", listOf("days dev", "daisy dev"))
                try {
                    store.saveEntry("p", "Other", listOf("days dev"), expected = before)
                    fail("Expected the stale edit to be refused")
                } catch (e: IllegalStateException) {
                    assertTrue(e.message!!.contains("changed on another device"))
                }
                store.setHost("h1")
                store.sync(adopt = true)
                assertTrue(
                    store.view.value.status
                        .contains("copy is kept"),
                )
                assertEquals(
                    listOf("days dev", "daisy dev"),
                    store.dictionary.entries
                        .getValue("p")[0]
                        .aliases,
                )
            } finally {
                dir.deleteRecursively()
            }
        }

    @Test fun aDamagedFileIsKeptAndEditsAreRefused() =
        runBlocking {
            val dir = folder()
            try {
                val file = File(dir, "dictionary.json")
                file.writeText("{not json")
                val store = VoiceDictionaryStore(file) { _, _, _ -> error("offline") }
                store.load()
                assertTrue(
                    store.view.value.status
                        .startsWith("Dictionary could not load"),
                )
                try {
                    store.saveEntry(null, "X", listOf("y"))
                    fail("Expected edits to be refused before a load")
                } catch (_: IllegalStateException) {
                }
                assertEquals("{not json", file.readText())
            } finally {
                dir.deleteRecursively()
            }
        }
}

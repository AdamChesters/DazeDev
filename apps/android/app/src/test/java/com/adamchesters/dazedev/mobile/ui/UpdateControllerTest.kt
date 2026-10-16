package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.releases.MobileUpdate
import com.adamchesters.dazedev.mobile.releases.ReleaseSource
import com.adamchesters.dazedev.mobile.releases.UpdateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateControllerTest {
    private class Store : UpdateStore {
        var history = "[]"
        var lastCheck = 0L
        var ignored = ""

        override fun releaseHistory() = history

        override fun saveReleaseHistory(value: String) {
            history = value
        }

        override fun lastUpdateCheck() = lastCheck

        override fun checkedUpdates() {
            lastCheck = 1
        }

        override fun ignoredUpdate() = ignored

        override fun ignoreUpdate(version: String) {
            ignored = version
        }
    }

    private class Source : ReleaseSource {
        var update: MobileUpdate? = null
        var failure: Exception? = null
        var historyJson = "[]"
        var checks = 0

        override fun updates(current: String): MobileUpdate? {
            checks++
            failure?.let { throw it }
            return update
        }

        override fun history() = historyJson
    }

    private class Setup(
        test: TestScope,
        now: Long = 0,
    ) {
        val dispatcher = StandardTestDispatcher(test.testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val ui = UiStore(DazeDevMobileUiState())
        val store = Store()
        val source = Source()
        val updates = UpdateController(ui, scope, store, source, "0.1.0", dispatcher) { now }
    }

    private val release = MobileUpdate("0.2.0", "https://github.com/x/y.apk", "Notes")

    @Test fun manualCheckShowsAnUpdateAndClearsTheBusyFlag() =
        runTest {
            val s = Setup(this)
            s.source.update = release
            s.store.ignored = "0.2.0"
            s.updates.checkUpdates()
            assertTrue(s.ui.value.checkingUpdates)
            advanceUntilIdle()
            assertFalse(s.ui.value.checkingUpdates)
            assertEquals(release, s.ui.value.mobileUpdate)
            assertEquals(1L, s.store.lastCheck)
            s.scope.cancel()
        }

    @Test fun automaticCheckSkipsAnIgnoredVersionQuietly() =
        runTest {
            val s = Setup(this)
            s.source.update = release
            s.store.ignored = "0.2.0"
            s.updates.checkUpdates(false)
            advanceUntilIdle()
            assertNull(s.ui.value.mobileUpdate)
            assertNull(s.ui.value.message)
            s.scope.cancel()
        }

    @Test fun manualCheckExplainsWhenNothingIsNewerOrTheCheckFails() =
        runTest {
            val s = Setup(this)
            s.updates.checkUpdates()
            advanceUntilIdle()
            assertEquals(
                "No newer public Android release is available. Development APKs can be installed offline.",
                s.ui.value.message
                    ?.text,
            )
            s.source.failure = IllegalStateException("Offline")
            s.updates.checkUpdates()
            advanceUntilIdle()
            assertEquals(
                "Offline",
                s.ui.value.message
                    ?.text,
            )
            assertFalse(s.ui.value.checkingUpdates)
            s.scope.cancel()
        }

    @Test fun dismissingRemembersTheVersion() =
        runTest {
            val s = Setup(this)
            s.source.update = release
            s.updates.checkUpdates()
            advanceUntilIdle()
            s.updates.dismissUpdate()
            assertNull(s.ui.value.mobileUpdate)
            assertEquals("0.2.0", s.store.ignored)
            s.scope.cancel()
        }

    @Test fun startChecksOnlyOnceADay() =
        runTest {
            val s = Setup(this, now = 1_000L)
            s.store.lastCheck = 0
            s.updates.start("[]")
            advanceUntilIdle()
            assertEquals(0, s.source.checks)
            val later = Setup(this, now = 25 * 60 * 60 * 1000L)
            later.updates.start("[]")
            advanceUntilIdle()
            assertEquals(1, later.source.checks)
            s.scope.cancel()
            later.scope.cancel()
        }

    @Test fun releaseHistoryMergesPublicEntriesAndKeepsThemOnBadData() =
        runTest {
            val s = Setup(this)
            s.source.historyJson = """[{"platform":"mobile","version":"0.2.0","date":"2026-10-01","notes":"New"}]"""
            s.updates.refreshReleaseHistory()
            advanceUntilIdle()
            assertEquals(
                listOf("0.2.0"),
                s.ui.value.releaseHistory
                    .map { it.version },
            )
            assertEquals("", s.ui.value.releaseHistoryStatus)
            assertTrue(s.store.history.contains("0.2.0"))
            s.source.historyJson = "not json"
            s.updates.refreshReleaseHistory()
            advanceUntilIdle()
            assertEquals(
                listOf("0.2.0"),
                s.ui.value.releaseHistory
                    .map { it.version },
            )
            s.scope.cancel()
        }
}

package com.adamchesters.dazedev.mobile.ui

import com.adamchesters.dazedev.mobile.host.FakeHostLink
import com.adamchesters.dazedev.mobile.host.FakeLinkFactory
import com.adamchesters.dazedev.mobile.host.HostRegistry
import com.adamchesters.dazedev.mobile.host.fakeRegistry
import com.adamchesters.dazedev.mobile.host.tailscaleProfile
import com.adamchesters.dazedev.mobile.ssh.AttachRequest
import com.adamchesters.dazedev.mobile.ssh.SshLink
import com.adamchesters.dazedev.mobile.ssh.TerminalLink
import com.adamchesters.dazedev.mobile.ssh.TerminalOpener
import com.adamchesters.dazedev.mobile.tmux.TmuxSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeTerminal : TerminalLink {
    override val output = emptyFlow<ByteArray>()
    override val state = MutableStateFlow(true)
    override val connected get() = state.value
    var closed = false
    val sizes = mutableListOf<Pair<Int, Int>>()

    override fun send(bytes: ByteArray) = connected

    override fun resize(
        cols: Int,
        rows: Int,
    ) {
        sizes.add(cols to rows)
    }

    override suspend fun cancelCopyMode() = Unit

    override fun close() {
        closed = true
        state.value = false
    }
}

/** Opens fake terminals; with [hold] set, each attach waits until its gate completes. */
class FakeOpener : TerminalOpener {
    var hold = false
    val requests = mutableListOf<AttachRequest>()
    val gates = mutableListOf<CompletableDeferred<Unit>>()
    val opened = mutableListOf<FakeTerminal>()
    var cancelled = 0

    override suspend fun open(
        ssh: SshLink,
        scope: CoroutineScope,
        request: AttachRequest,
    ): TerminalLink {
        requests.add(request)
        val gate = CompletableDeferred<Unit>()
        gates.add(gate)
        if (hold) {
            try {
                gate.await()
            } catch (e: CancellationException) {
                cancelled++
                throw e
            }
        }
        return FakeTerminal().also { opened.add(it) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalControllerTest {
    private class Setup(
        test: TestScope,
    ) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val factory = FakeLinkFactory()
        val registry: HostRegistry = fakeRegistry(scope, factory)
        val ui = UiStore(DazeDevMobileUiState(profile = tailscaleProfile(), selectedPaneId = "pane-1"))
        val opener = FakeOpener()
        val terminal = TerminalController(ui, registry, scope, opener)
        val link get() = factory.hosts.single()
        val host get() = registry.entry("host-1")!!
        val rebound = mutableListOf<Pair<String, TmuxSession>>()

        init {
            terminal.rebindPane = { pane, session -> rebound.add(pane to session) }
            registry.connect(tailscaleProfile(), CharArray(0))
        }

        fun measure(
            cols: Int,
            rows: Int,
        ) = terminal.terminalMeasured(ui.value.terminalEpoch, cols, rows)
    }

    @Test fun attachesAtTheMeasuredSize() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.terminal.openSession(s.host, "$1", "s1", "main")
            s.measure(100, 30)
            advanceUntilIdle()
            assertEquals(AttachRequest("", "$1", "s1", 100, 30, null), s.opener.requests.single())
            assertSame(s.opener.opened.single(), s.ui.value.terminal)
            assertEquals("main", s.ui.value.selectedSessionName)
            s.scope.cancel()
        }

    @Test fun aNewerAttachCancelsTheOlderOne() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.opener.hold = true
            s.terminal.openSession(s.host, "$1", "s1", "main")
            val firstEpoch = s.ui.value.terminalEpoch
            s.measure(100, 30)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, s.opener.requests.size)
            s.terminal.openSession(s.host, "$2", "s2", "other")
            assertTrue(s.ui.value.terminalEpoch > firstEpoch)
            // A size measured for the old attachment is ignored.
            s.terminal.terminalMeasured(firstEpoch, 50, 10)
            s.measure(90, 20)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1, s.opener.cancelled)
            assertEquals(AttachRequest("", "$2", "s2", 90, 20, null), s.opener.requests[1])
            s.opener.gates[1].complete(Unit)
            advanceUntilIdle()
            assertSame(s.opener.opened.single(), s.ui.value.terminal)
            assertEquals("other", s.ui.value.selectedSessionName)
            s.scope.cancel()
        }

    @Test fun leavingBeforeTheAttachFinishesOpensNothing() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.terminal.openSession(s.host, "$1", "s1", "main")
            s.terminal.leaveTerminal()
            advanceUntilIdle()
            assertTrue(s.opener.requests.isEmpty())
            assertNull(s.ui.value.terminal)
            assertNull(s.ui.value.selectedSessionName)
            s.scope.cancel()
        }

    @Test fun aRestartedSessionOffersTheReplacementAndAdoptsIt() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.link.replies["session_context"] = { error("Session changed. Go back and open it again.") }
            s.link.replies["sessions"] = { JSONArray().put(FakeHostLink.session("main", "$9", "s9")) }
            s.terminal.openSession(s.host, "$1", "s1", "main")
            advanceUntilIdle()
            val stale = s.ui.value.staleSession!!
            assertEquals("Session ended or restarted.", s.ui.value.terminalError)
            assertEquals("host-1", stale.hostId)
            assertEquals("$9", stale.replacement?.id)

            s.link.replies["session_context"] = { "1:2:3:4" }
            s.terminal.adoptStaleSession()
            s.measure(80, 24)
            advanceUntilIdle()
            assertNull(s.ui.value.staleSession)
            assertEquals(listOf("pane-1" to stale.replacement!!), s.rebound)
            assertEquals(
                "$9",
                s.opener.requests
                    .single()
                    .id,
            )
            assertEquals("s9", s.ui.value.selectedStamp)
            s.scope.cancel()
        }

    @Test fun backgroundGracePeriodDetachesAndReturningReattaches() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.terminal.openSession(s.host, "$1", "s1", "main")
            s.measure(80, 24)
            advanceUntilIdle()
            val first = s.opener.opened.single()
            s.terminal.setForeground(false)
            advanceTimeBy(119_000)
            runCurrent()
            assertSame(first, s.ui.value.terminal)
            advanceTimeBy(2_000)
            runCurrent()
            assertNull(s.ui.value.terminal)
            assertTrue(first.closed)
            s.terminal.setForeground(true)
            s.measure(80, 24)
            advanceUntilIdle()
            assertEquals(2, s.opener.requests.size)
            assertSame(s.opener.opened[1], s.ui.value.terminal)
            s.scope.cancel()
        }

    @Test fun aLostHostLinkClosesItsTerminal() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.terminal.openSession(s.host, "$1", "s1", "main")
            s.measure(80, 24)
            advanceUntilIdle()
            s.terminal.linkLost("host-1", "Lost connection to Dev. gone")
            assertNull(s.ui.value.terminal)
            assertTrue(
                s.opener.opened
                    .single()
                    .closed,
            )
            assertEquals("Lost connection to Dev. gone", s.ui.value.terminalError)
            s.scope.cancel()
        }

    @Test fun keepingOtherDevicesSizeAttachesWithIgnoreSizeOnlyOnNewTmux() =
        runTest {
            val s = Setup(this)
            advanceUntilIdle()
            s.terminal.keepOtherSize = { true }
            s.factory.ssh
                .single()
                .output = "tmux 3.4\n"
            s.terminal.openSession(s.host, "$1", "s1", "main")
            s.measure(80, 24)
            advanceUntilIdle()
            assertTrue(
                s.opener.requests
                    .single()
                    .ignoreSize,
            )

            val old = Setup(this)
            advanceUntilIdle()
            old.terminal.keepOtherSize = { true }
            old.factory.ssh
                .single()
                .output = "tmux 3.1c\n"
            old.terminal.openSession(old.host, "$1", "s1", "main")
            old.measure(80, 24)
            advanceUntilIdle()
            assertEquals(
                false,
                old.opener.requests
                    .single()
                    .ignoreSize,
            )
            assertTrue(
                old.ui.value.message!!
                    .text
                    .contains("older than 3.2"),
            )
            s.scope.cancel()
            old.scope.cancel()
        }
}

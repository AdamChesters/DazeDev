package com.adamchesters.dazedev.mobile.host

import com.adamchesters.dazedev.mobile.data.AuthMethod
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Returning to the screen, liveness checks and the background window. */
@OptIn(ExperimentalCoroutinesApi::class)
class HostResumeTest {
    private class Setup(
        test: TestScope,
        window: BackgroundWindow = BackgroundWindow.FiveMinutes,
    ) {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(test.testScheduler))
        val factory = FakeLinkFactory()

        /** Extra time that passed while the phone slept, which the test scheduler does not see. */
        var slept = 0L
        val registry = fakeRegistry(scope, factory, clock = { test.testScheduler.currentTime + slept })
        val events = mutableListOf<HostEvent>()

        init {
            registry.backgroundWindow.value = window
            scope.launch { registry.events.toList(events) }
        }

        fun link(i: Int = 0) = factory.hosts[i]

        fun ssh(i: Int = 0) = factory.ssh[i]

        fun host(id: String = "host-1") = registry.snapshot(id)!!
    }

    @Test fun aHostThatAnswersOnReturnStaysConnected() =
        runTest {
            val s = Setup(this)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            advanceTimeBy(60_000)
            s.registry.setForeground(true)
            assertTrue(s.host().checking)
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            advanceUntilIdle()
            assertFalse(s.host().checking)
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            assertTrue(s.link().requests.contains("ping"))
            assertTrue(s.events.contains(HostEvent.Checked("host-1")))
            assertEquals(1, s.ssh().connects)
            s.scope.cancel()
        }

    @Test fun aHostThatDoesNotAnswerOnReturnDisconnectsWithAReason() =
        runTest {
            val s = Setup(this)
            s.registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            s.registry.setForeground(false)
            s.link().stalled.add("ping")
            s.registry.setForeground(true)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(s.host("pw").checking)
            advanceTimeBy(2_000)
            runCurrent()
            val host = s.host("pw")
            val reason = "Lost connection to Password host. It did not answer after the app returned to the screen."
            assertFalse(host.checking)
            assertEquals(ConnectionPhase.Disconnected, host.phase)
            assertEquals(reason, host.linkError)
            assertTrue(s.events.contains(HostEvent.LinkLost("pw", reason)))
            assertFalse(s.link().isOpen)
            assertFalse(s.ssh().isConnected)
            // A typed password is never reused: the host waits for the user.
            advanceUntilIdle()
            assertEquals(1, s.ssh().connects)
            assertFalse(s.host("pw").reconnecting)
            s.scope.cancel()
        }

    @Test fun aFailedCheckReconnectsAnUnattendedHostAtOnce() =
        runTest {
            val s = Setup(this)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            s.link().replies["ping"] = { error("Host stream closed.") }
            s.registry.setForeground(true)
            advanceUntilIdle()
            assertTrue(s.events.any { it is HostEvent.LinkLost })
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            assertFalse(s.host().reconnecting)
            assertEquals(2, s.ssh().connects)
            assertTrue(s.events.contains(HostEvent.Reconnected("host-1")))
            s.scope.cancel()
        }

    @Test fun aDeadSshSessionFailsTheCheckWithoutWaiting() =
        runTest {
            val s = Setup(this)
            s.registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            s.registry.setForeground(false)
            s.ssh().isConnected = false
            s.registry.setForeground(true)
            runCurrent()
            assertEquals(ConnectionPhase.Disconnected, s.host("pw").phase)
            assertFalse(s.link().requests.contains("ping"))
            s.scope.cancel()
        }

    @Test fun theWindowEndingDisconnectsRecordsWhyAndReturnReconnectsUnattendedHostsOnly() =
        runTest {
            val s = Setup(this)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            s.registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            s.registry.setForeground(false)
            assertEquals(currentTime + 300_000, s.registry.backgroundEndsAt.value)
            advanceTimeBy(299_000)
            runCurrent()
            assertEquals(
                listOf(ConnectionPhase.Connected, ConnectionPhase.Connected),
                s.registry.hosts.value
                    .map { it.phase },
            )
            advanceTimeBy(2_000)
            runCurrent()
            val reason = "Disconnected after 5 minutes in the background."
            assertEquals(
                listOf(ConnectionPhase.Disconnected, ConnectionPhase.Disconnected),
                s.registry.hosts.value
                    .map { it.phase },
            )
            assertEquals(
                listOf(reason, reason),
                s.registry.hosts.value
                    .map { it.linkError },
            )
            assertTrue(s.events.containsAll(listOf(HostEvent.LinkLost("host-1", reason), HostEvent.LinkLost("pw", reason))))
            assertFalse(s.factory.hosts.any { it.isOpen })
            // Nothing reconnects while the app stays in the background.
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(listOf(1, 1), s.factory.ssh.map { it.connects })

            s.registry.setForeground(true)
            assertNull(s.registry.backgroundEndsAt.value)
            advanceUntilIdle()
            assertTrue(s.events.contains(HostEvent.Message(reason)))
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            assertTrue(s.events.contains(HostEvent.Reconnected("host-1")))
            assertEquals(ConnectionPhase.Disconnected, s.host("pw").phase)
            assertEquals(reason, s.host("pw").linkError)
            assertEquals(listOf(2, 1), s.factory.ssh.map { it.connects })
            s.scope.cancel()
        }

    @Test fun aWindowThatEndedWhileThePhoneSleptIsAppliedOnReturn() =
        runTest {
            val s = Setup(this, BackgroundWindow.FifteenMinutes)
            s.registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            s.registry.setForeground(false)
            advanceTimeBy(60_000)
            runCurrent()
            // Deep sleep: the elapsed clock moves on, the timer has not fired.
            s.slept = 20 * 60_000L
            s.registry.setForeground(true)
            runCurrent()
            assertEquals(ConnectionPhase.Disconnected, s.host("pw").phase)
            assertEquals("Disconnected after 15 minutes in the background.", s.host("pw").linkError)
            assertFalse(s.link().requests.contains("ping"))
            s.scope.cancel()
        }

    @Test fun returningInsideTheWindowCancelsIt() =
        runTest {
            val s = Setup(this)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            advanceTimeBy(200_000)
            s.registry.setForeground(true)
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            assertTrue(s.events.none { it is HostEvent.LinkLost })
            s.scope.cancel()
        }

    @Test fun untilIDisconnectHasNoTimeLimit() =
        runTest {
            val s = Setup(this, BackgroundWindow.UntilDisconnect)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            assertNull(s.registry.backgroundEndsAt.value)
            advanceTimeBy(24 * 3_600_000L)
            runCurrent()
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            s.scope.cancel()
        }

    @Test fun linksLostInsideTheWindowReconnectInTheBackground() =
        runTest {
            val s = Setup(this)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            s.link().lost.emit(HostLinkEvent.Lost("Network changed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(ConnectionPhase.Connected, s.host().phase)
            assertEquals(2, s.ssh().connects)
            s.scope.cancel()
        }

    @Test fun aStoredKeyWithATypedPassphraseIsNotUnattended() =
        runTest {
            val credentials = FakeCredentials()
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory, credentials = credentials, clock = { testScheduler.currentTime })
            credentials.keys["host-1"] = byteArrayOf(1)
            val key = tailscaleProfile().copy(authMethod = AuthMethod.PrivateKey, keyStored = true)
            registry.connect(key, "typed".toCharArray())
            advanceUntilIdle()
            assertFalse(registry.entry("host-1")!!.unattended)
            registry.setForeground(false)
            advanceTimeBy(301_000)
            runCurrent()
            registry.setForeground(true)
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Disconnected, registry.snapshot("host-1")!!.phase)
            assertEquals(1, factory.ssh.single().connects)
            scope.cancel()
        }

    @Test fun disconnectingByHandIsNotUndoneOnReturn() =
        runTest {
            val s = Setup(this, BackgroundWindow.Off)
            s.registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            s.registry.setForeground(false)
            s.link().lost.emit(HostLinkEvent.Lost("gone"))
            advanceUntilIdle()
            s.registry.disconnect("host-1")
            s.registry.setForeground(true)
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Disconnected, s.host().phase)
            assertEquals(1, s.ssh().connects)
            s.scope.cancel()
        }

    @Test fun theSavedSettingMigratesFromTheOnOffSwitch() {
        assertEquals(BackgroundWindow.UntilDisconnect, BackgroundWindow.stored(null, legacyKeepConnected = true))
        assertEquals(BackgroundWindow.FiveMinutes, BackgroundWindow.stored(null, legacyKeepConnected = false))
        assertEquals(BackgroundWindow.OneHour, BackgroundWindow.stored("OneHour", legacyKeepConnected = true))
        assertEquals(BackgroundWindow.Off, BackgroundWindow.stored("Off", legacyKeepConnected = false))
        assertEquals(BackgroundWindow.Default, BackgroundWindow.stored("Forever", legacyKeepConnected = false))
    }
}

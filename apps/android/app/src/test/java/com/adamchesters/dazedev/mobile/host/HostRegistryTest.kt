package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostRegistryTest {
    @Test fun connectsListsSessionsAndSavesTheProfile() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val store = FakeHostStore()
            val registry = fakeRegistry(scope, store = store)
            var connected = false
            assertTrue(registry.connect(tailscaleProfile(), CharArray(0), onConnected = { connected = true }))
            assertEquals(ConnectionPhase.Connecting, registry.snapshot("host-1")?.phase)
            advanceUntilIdle()
            val host = registry.snapshot("host-1")!!
            assertEquals(ConnectionPhase.Connected, host.phase)
            assertEquals("machine:user:", host.fingerprint)
            assertEquals(listOf("main"), host.sessions.map { it.name })
            assertTrue(connected)
            assertEquals("machine:user:", store.saved["host-1"]?.fingerprint)
            scope.cancel()
        }

    @Test fun linkLostDisconnectsWithAReasonAndReconnectWorks() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            val link = factory.hosts.single()
            link.lost.emit(HostLinkEvent.Lost("Network unreachable"))
            advanceUntilIdle()
            val lost = registry.snapshot("pw")!!
            assertEquals(ConnectionPhase.Disconnected, lost.phase)
            assertEquals("Lost connection to Password host. Network unreachable", lost.linkError)
            assertFalse(link.isOpen)
            assertFalse(factory.ssh.single().isConnected)
            assertEquals(HostEvent.LinkLost("pw", "Lost connection to Password host. Network unreachable"), events.single { it is HostEvent.LinkLost })

            assertTrue(registry.connect(passwordProfile(), "secret".toCharArray()))
            advanceUntilIdle()
            val again = registry.snapshot("pw")!!
            assertEquals(ConnectionPhase.Connected, again.phase)
            assertNull(again.linkError)
            assertEquals(2, link.starts)
            assertEquals(2, factory.ssh.single().connects)
            watcher.cancel()
            scope.cancel()
        }

    @Test fun aLostLinkWhileDisconnectedIsIgnored() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            registry.disconnect("host-1")
            factory.hosts
                .single()
                .lost
                .emit(HostLinkEvent.Lost("closed"))
            advanceUntilIdle()
            assertNull(registry.snapshot("host-1")!!.linkError)
            scope.cancel()
        }

    @Test fun aFailedConnectReportsAndStaysDisconnected() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(tailscaleProfile(), CharArray(0), verify = { error("Different host") })
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Disconnected, registry.snapshot("host-1")!!.phase)
            assertEquals(HostEvent.Message("Different host"), events.single())
            watcher.cancel()
            scope.cancel()
        }

    @Test fun disconnectAllClosesEveryHostAndReportsIt() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(tailscaleProfile("a"), CharArray(0))
            registry.connect(tailscaleProfile("b"), CharArray(0))
            advanceUntilIdle()
            registry.disconnectAll()
            advanceUntilIdle()
            assertEquals(listOf(ConnectionPhase.Disconnected, ConnectionPhase.Disconnected), registry.hosts.value.map { it.phase })
            assertEquals(listOf(HostEvent.Disconnected("a"), HostEvent.Disconnected("b")), events)
            assertFalse(factory.hosts.any { it.isOpen })
            watcher.cancel()
            scope.cancel()
        }

    @Test fun anUnattendedHostReconnectsAfterLinkLoss() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            factory.hosts
                .single()
                .lost
                .emit(HostLinkEvent.Lost("Network changed"))
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Connected, registry.snapshot("host-1")!!.phase)
            assertEquals(2, factory.ssh.single().connects)
            assertTrue(events.contains(HostEvent.Reconnected("host-1")))
            watcher.cancel()
            scope.cancel()
        }

    @Test fun reconnectGivesUpAfterThreeAttemptsAndShowsDisconnected() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            factory.ssh.single().refuse = true
            factory.hosts
                .single()
                .lost
                .emit(HostLinkEvent.Lost("Network changed"))
            advanceUntilIdle()
            val host = registry.snapshot("host-1")!!
            assertEquals(ConnectionPhase.Disconnected, host.phase)
            assertEquals("Lost connection to Dev. Network changed", host.linkError)
            assertEquals(4, factory.ssh.single().connects)
            // Quiet attempts: no failure messages pile up.
            assertTrue(events.none { it is HostEvent.Message })
            watcher.cancel()
            scope.cancel()
        }

    @Test fun withoutABackgroundWindowOrWithATypedPasswordThereIsNoReconnectInTheBackground() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            registry.backgroundWindow.value = BackgroundWindow.Off
            registry.connect(tailscaleProfile(), CharArray(0))
            registry.connect(passwordProfile(), "secret".toCharArray())
            advanceUntilIdle()
            registry.setForeground(false)
            factory.hosts[0].lost.emit(HostLinkEvent.Lost("gone"))
            factory.hosts[1].lost.emit(HostLinkEvent.Lost("gone"))
            advanceUntilIdle()
            assertEquals(listOf(ConnectionPhase.Disconnected, ConnectionPhase.Disconnected), registry.hosts.value.map { it.phase })
            assertEquals(listOf(1, 1), factory.ssh.map { it.connects })
            // Back on screen, only the host that needs no typed secret reconnects.
            registry.setForeground(true)
            advanceUntilIdle()
            assertEquals(listOf(ConnectionPhase.Connected, ConnectionPhase.Disconnected), registry.hosts.value.map { it.phase })
            assertEquals(listOf(2, 1), factory.ssh.map { it.connects })
            assertEquals("Lost connection to Password host. gone", registry.snapshot("pw")!!.linkError)
            scope.cancel()
        }

    @Test fun storedKeysMigrateRememberedPassphrasesAreUsedAndRemovalForgetsThem() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val store = FakeHostStore()
            val credentials = FakeCredentials()
            val registry = fakeRegistry(scope, factory, store, credentials)
            val profile =
                tailscaleProfile().copy(
                    authMethod = com.adamchesters.dazedev.mobile.data.AuthMethod.PrivateKey,
                    privateKeyUri = "content://old/key",
                    rememberPassphrase = true,
                )
            credentials.keys["host-1"] = byteArrayOf(1, 2, 3)
            val typed = "typed".toCharArray()
            registry.connect(profile, typed)
            advanceUntilIdle()
            val saved = store.saved.getValue("host-1")
            assertTrue(saved.keyStored)
            assertNull(saved.privateKeyUri)
            assertEquals("typed", credentials.passphrases["host-1"])
            assertTrue(typed.all { it == '\u0000' })
            registry.disconnect("host-1")
            registry.connect(saved, CharArray(0))
            advanceUntilIdle()
            assertEquals(listOf("typed", "typed"), factory.ssh.single().secrets)
            assertEquals(ConnectionPhase.Connected, registry.snapshot("host-1")!!.phase)
            registry.remove("host-1")
            assertNull(registry.snapshot("host-1"))
            assertTrue(store.saved.isEmpty())
            assertEquals(listOf("host-1"), credentials.forgotten)
            assertFalse(factory.hosts.single().isOpen)
            scope.cancel()
        }

    @Test fun networkLossWaitsAndReconnectsWhenTheNetworkReturns() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            val events = mutableListOf<HostEvent>()
            val watcher = scope.launch { registry.events.toList(events) }
            registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            registry.networkLost()
            advanceUntilIdle()
            val waiting = registry.snapshot("host-1")!!
            assertEquals(ConnectionPhase.Disconnected, waiting.phase)
            assertEquals("Waiting for the network to reconnect Dev.", waiting.linkError)
            assertTrue(events.any { it is HostEvent.LinkLost })
            // No attempt runs while the network is gone.
            assertEquals(1, factory.ssh.single().connects)
            registry.networkAvailable()
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Connected, registry.snapshot("host-1")!!.phase)
            assertEquals(2, factory.ssh.single().connects)
            assertTrue(events.contains(HostEvent.Reconnected("host-1")))
            watcher.cancel()
            scope.cancel()
        }

    @Test fun networkLossInTheBackgroundWithoutAWindowOnlyDisconnectsUntilReturn() =
        runTest {
            val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val factory = FakeLinkFactory()
            val registry = fakeRegistry(scope, factory)
            registry.backgroundWindow.value = BackgroundWindow.Off
            registry.connect(tailscaleProfile(), CharArray(0))
            advanceUntilIdle()
            registry.setForeground(false)
            registry.networkLost()
            registry.networkAvailable()
            advanceUntilIdle()
            val host = registry.snapshot("host-1")!!
            assertEquals(ConnectionPhase.Disconnected, host.phase)
            assertEquals("Lost connection to Dev. The phone lost its network connection.", host.linkError)
            assertEquals(1, factory.ssh.single().connects)
            registry.setForeground(true)
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Connected, registry.snapshot("host-1")!!.phase)
            assertEquals(2, factory.ssh.single().connects)
            scope.cancel()
        }
}

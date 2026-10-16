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
            assertTrue(registry.connect(tailscaleProfile(), "", onConnected = { connected = true }))
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
            registry.connect(tailscaleProfile(), "")
            advanceUntilIdle()
            val link = factory.hosts.single()
            link.lost.emit(HostLinkEvent.Lost("Network unreachable"))
            advanceUntilIdle()
            val lost = registry.snapshot("host-1")!!
            assertEquals(ConnectionPhase.Disconnected, lost.phase)
            assertEquals("Lost connection to Dev. Network unreachable", lost.linkError)
            assertFalse(link.isOpen)
            assertFalse(factory.ssh.single().isConnected)
            assertEquals(HostEvent.LinkLost("host-1", "Lost connection to Dev. Network unreachable"), events.single { it is HostEvent.LinkLost })

            assertTrue(registry.connect(tailscaleProfile(), ""))
            advanceUntilIdle()
            val again = registry.snapshot("host-1")!!
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
            registry.connect(tailscaleProfile(), "")
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
            registry.connect(tailscaleProfile(), "", verify = { error("Different host") })
            advanceUntilIdle()
            assertEquals(ConnectionPhase.Disconnected, registry.snapshot("host-1")!!.phase)
            assertEquals(HostEvent.Message("Different host"), events.single())
            watcher.cancel()
            scope.cancel()
        }
}

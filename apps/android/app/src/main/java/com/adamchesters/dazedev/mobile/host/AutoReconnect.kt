package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** While "keep connected" is on, reconnects a host that lost its link without asking for a secret. */
internal class AutoReconnect(
    private val registry: HostRegistry,
    private val delays: List<Long> = listOf(2_000L, 8_000L, 30_000L),
) {
    fun start(
        h: HostEntry,
        error: String,
    ) {
        h.reconnect?.cancel()
        h.reconnect =
            registry.scope.launch {
                for (wait in delays) {
                    delay(wait)
                    if (!registry.keepConnected.value || h.phase == ConnectionPhase.Connected) return@launch
                    h.connectJob?.join()
                    if (h.phase == ConnectionPhase.Connected) return@launch
                    if (!registry.connect(h.profile, CharArray(0), onConnected = registry::reconnected, quiet = true)) continue
                    h.connectJob?.join()
                    if (h.phase == ConnectionPhase.Connected) return@launch
                }
                // All attempts failed: show the usual Disconnected state with the reason.
                h.linkError = error
                registry.publish()
            }
    }
}

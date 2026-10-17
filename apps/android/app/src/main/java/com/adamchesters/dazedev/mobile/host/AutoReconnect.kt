package com.adamchesters.dazedev.mobile.host

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Reconnects a host that lost its link without asking for a secret, while the registry allows it
 * (app on screen, or inside the background window).
 */
internal class AutoReconnect(
    private val registry: HostRegistry,
    private val delays: List<Long> = listOf(2_000L, 8_000L, 30_000L),
) {
    /** On return to the screen the first attempt starts at once. */
    val onReturn = listOf(0L, 2_000L, 8_000L)

    fun start(
        h: HostEntry,
        error: String,
        waits: List<Long>? = null,
    ) {
        h.reconnect?.cancel()
        h.resumeOnReturn = false
        h.reconnecting = true
        val job =
            registry.scope.launch(start = CoroutineStart.LAZY) {
                try {
                    for (wait in waits ?: delays) {
                        delay(wait)
                        if (h.phase == ConnectionPhase.Connected) return@launch
                        if (!registry.mayReconnect()) {
                            // The app left the screen and the background window does not cover it.
                            h.resumeOnReturn = true
                            h.linkError = error
                            return@launch
                        }
                        h.connectJob?.join()
                        if (h.phase == ConnectionPhase.Connected) return@launch
                        if (!registry.connect(h.profile, CharArray(0), onConnected = registry::reconnected, quiet = true)) continue
                        h.connectJob?.join()
                        if (h.phase == ConnectionPhase.Connected) return@launch
                    }
                    // All attempts failed: show the usual Disconnected state with the reason.
                    h.linkError = error
                } finally {
                    if (h.reconnect === coroutineContext[Job]) {
                        h.reconnecting = false
                        registry.publish()
                    }
                }
            }
        h.reconnect = job
        registry.publish()
        job.start()
    }
}

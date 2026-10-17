package com.adamchesters.dazedev.mobile.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Runs [KeepConnectedService] while the background setting keeps connections and a host is connected
 * or reconnecting. Android 12 and later refuse to start it from the background, so it starts while the
 * app is on screen (when the first host connects) and keeps running through the background window.
 */
class KeepConnected(
    private val context: Context,
    private val registry: HostRegistry,
) {
    private var running = false

    private data class Status(
        /** Connected hosts, or null when the service should not run. */
        val count: Int?,
        val endsAt: Long?,
        val foreground: Boolean,
    )

    fun start() {
        KeepConnectedService.channel(context)
        registry.scope.launch {
            combine(registry.hosts, registry.backgroundWindow, registry.backgroundEndsAt, registry.foreground) { hosts, window, endsAt, foreground ->
                val connected = hosts.count { it.phase == ConnectionPhase.Connected }
                val needed = window.keepsConnections && (connected > 0 || hosts.any { it.reconnecting })
                Status(if (needed) connected else null, endsAt, foreground)
            }.distinctUntilChanged()
                .collect(::update)
        }
    }

    private fun update(status: Status) {
        val count = status.count
        if (count == null) {
            if (running) context.stopService(Intent(context, KeepConnectedService::class.java))
            running = false
            return
        }
        // The countdown is shown as a wall-clock time; the registry counts in elapsed time.
        val endsAt = status.endsAt?.let { System.currentTimeMillis() + (it - SystemClock.elapsedRealtime()) }
        if (running) {
            if (notificationsAllowed()) {
                context.getSystemService(NotificationManager::class.java).notify(KeepConnectedService.NOTIFICATION_ID, KeepConnectedService.notification(context, count, endsAt))
            }
            return
        }
        // A start from the background would be refused; the next return to the screen retries.
        if (!status.foreground) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, KeepConnectedService::class.java).putExtra(KeepConnectedService.EXTRA_COUNT, count))
            running = true
        } catch (e: IllegalStateException) {
            Log.w("DazeDev", "Keep-connected service not started", e)
        }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

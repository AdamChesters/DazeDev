package com.adamchesters.dazedev.mobile.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.adamchesters.dazedev.mobile.host.ConnectionPhase
import com.adamchesters.dazedev.mobile.host.HostRegistry
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** Runs [KeepConnectedService] while the setting is on and at least one host is connected. */
class KeepConnected(
    private val context: Context,
    private val registry: HostRegistry,
    private val enabled: StateFlow<Boolean>,
) {
    private var running = false

    fun start() {
        KeepConnectedService.channel(context)
        registry.scope.launch {
            combine(registry.hosts, enabled) { hosts, on -> if (on) hosts.count { it.phase == ConnectionPhase.Connected } else 0 }
                .distinctUntilChanged()
                .collect(::update)
        }
    }

    private fun update(count: Int) {
        if (count == 0) {
            if (running) context.stopService(Intent(context, KeepConnectedService::class.java))
            running = false
            return
        }
        if (running) {
            if (notificationsAllowed()) {
                context.getSystemService(NotificationManager::class.java).notify(KeepConnectedService.NOTIFICATION_ID, KeepConnectedService.notification(context, count))
            }
            return
        }
        try {
            ContextCompat.startForegroundService(context, Intent(context, KeepConnectedService::class.java).putExtra(KeepConnectedService.EXTRA_COUNT, count))
            running = true
        } catch (e: IllegalStateException) {
            // Android refuses to start it while the app is in the background; the next change in the foreground retries.
            Log.w("DazeDev", "Keep-connected service not started", e)
        }
    }

    private fun notificationsAllowed() = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

package com.adamchesters.dazedev.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.adamchesters.dazedev.mobile.DazeDevApplication
import com.adamchesters.dazedev.mobile.MainActivity
import com.adamchesters.dazedev.mobile.R

/** Keeps the app process, and so its host connections, alive while the app is in the background. */
class KeepConnectedService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val app = application as DazeDevApplication
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            app.hosts.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(this, intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1), type)
        // The registry restarts this service while it is needed; a killed process has no connections to keep.
        return START_NOT_STICKY
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val EXTRA_COUNT = "count"
        private const val CHANNEL = "keep-connected"
        private const val ACTION_DISCONNECT_ALL = "com.adamchesters.dazedev.mobile.DISCONNECT_ALL"

        fun channel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Background connections", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows while DazeDev keeps hosts connected in the background."
            channel.setShowBadge(false)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        fun notification(
            context: Context,
            count: Int,
        ): Notification {
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val disconnect =
                PendingIntent.getService(
                    context,
                    1,
                    Intent(context, KeepConnectedService::class.java).setAction(ACTION_DISCONNECT_ALL),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            return NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_connected)
                .setContentTitle(if (count == 1) "1 host connected" else "$count hosts connected")
                .setContentText("DazeDev keeps your SSH connections open in the background.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setContentIntent(open)
                .addAction(0, "Disconnect all", disconnect)
                .build()
        }
    }
}

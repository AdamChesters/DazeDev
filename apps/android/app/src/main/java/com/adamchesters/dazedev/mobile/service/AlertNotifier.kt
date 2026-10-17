package com.adamchesters.dazedev.mobile.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.adamchesters.dazedev.mobile.MainActivity
import com.adamchesters.dazedev.mobile.R

/** Local completion notifications for terminal alerts. Off unless the user turns them on. */
object AlertNotifier {
    private const val CHANNEL = "terminal-alerts"

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notify(
        context: Context,
        key: String,
        title: String,
        text: String,
    ) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Terminal alerts", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "Completion and notification markers from the open terminal while the app is in the background." })
        }
        val open =
            PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            NotificationCompat
                .Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_connected)
                .setContentTitle(title.take(80))
                .setContentText(text)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(open)
                .build()
        try {
            // Ids above the keep-connected notice's; one per terminal, so a newer alert replaces the older one.
            NotificationManagerCompat.from(context).notify(100 + (key.hashCode() and 0xffff), notification)
        } catch (_: SecurityException) {
            // Permission was withdrawn meanwhile.
        }
    }
}

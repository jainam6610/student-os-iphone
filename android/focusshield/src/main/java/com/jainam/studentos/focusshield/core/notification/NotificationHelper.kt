package com.jainam.studentos.focusshield.core.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jainam.studentos.focusshield.core.constants.AppConfig

class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val coreChannel = NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID_CORE,
                "Focus Shield",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Focus Shield session status and controls"
                setShowBadge(false)
            }

            val alertsChannel = NotificationChannel(
                AppConfig.NOTIFICATION_CHANNEL_ID_ALERTS,
                "Focus Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Focus session alerts and reminders"
            }

            notificationManager.createNotificationChannel(coreChannel)
            notificationManager.createNotificationChannel(alertsChannel)
        }
    }

    fun buildForegroundServiceNotification(
        sessionDuration: Long,
        secondsRemaining: Long,
        isPaused: Boolean,
        distractionCount: Int
    ): Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, Class.forName("com.jainam.studentos.MainActivity")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val elapsedMinutes = (sessionDuration - secondsRemaining) / 60
        val totalMinutes = sessionDuration / 60
        val remainingFormatted = formatTime(secondsRemaining)

        val contentText = if (isPaused) {
            "Session paused - $remainingFormatted remaining"
        } else {
            "Focus active - $remainingFormatted remaining (${elapsedMinutes}m / ${totalMinutes}m)"
        }

        return NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
            .setContentTitle("Focus Shield")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun buildSessionAlertNotification(title: String, message: String): Notification {
        return NotificationCompat.Builder(context, AppConfig.NOTIFICATION_CHANNEL_ID_ALERTS)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
    }

    private fun formatTime(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) {
            String.format("%d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format("%02d:%02d", mins, secs)
        }
    }

    fun sendAlert(notificationId: Int, title: String, message: String) {
        val notification = buildSessionAlertNotification(title, message)
        notificationManager.notify(notificationId, notification)
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }
}

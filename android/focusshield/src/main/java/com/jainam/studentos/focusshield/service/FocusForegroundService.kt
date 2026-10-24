package com.jainam.studentos.focusshield.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jainam.studentos.focusshield.core.constants.AppConfig
import com.jainam.studentos.focusshield.core.notification.NotificationHelper
import com.jainam.studentos.focusshield.manager.FocusSessionManager
import com.jainam.studentos.focusshield.manager.FocusSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FocusForegroundService : android.app.Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var sessionManager: FocusSessionManager
    private lateinit var dndService: DoNotDisturbService
    @Volatile
    private var isObserving = false

    companion object {
        private const val TAG = "[FocusForegroundService]"
        const val ACTION_PAUSE = "com.jainam.studentos.action.PAUSE_SESSION"
        const val ACTION_RESUME = "com.jainam.studentos.action.RESUME_SESSION"
        const val ACTION_STOP = "com.jainam.studentos.action.STOP_SESSION"
        const val ACTION_OPEN = "com.jainam.studentos.action.OPEN_APP"
        const val ACTION_COMPLETE = "com.jainam.studentos.action.COMPLETE_SESSION"

        private const val REQUEST_CODE_PAUSE = 1001
        private const val REQUEST_CODE_RESUME = 1002
        private const val REQUEST_CODE_STOP = 1003
        private const val REQUEST_CODE_OPEN = 1004
        const val REQUEST_CODE_COMPLETE = 1005
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate() — FocusForegroundService created")
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()
        dndService = DoNotDisturbService(this)
        sessionManager = FocusSessionManager.getInstance(this, dndService)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "START"
        Log.i(TAG, "onStartCommand() action=$action startId=$startId")
        try {
            val defaultNotification = NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
                .setContentTitle("Focus Shield")
                .setContentText("Initializing...")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, defaultNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            @Suppress("DEPRECATION")
            startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, defaultNotification)
        }
            Log.i(TAG, "onStartCommand() foreground notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand() FAILED to start foreground notification", e)
        }

        observeSession()

        when (action) {
            ACTION_PAUSE -> {
                Log.i(TAG, "onStartCommand() → PAUSE_SESSION")
                sessionManager.pauseSession()
            }
            ACTION_RESUME -> {
                Log.i(TAG, "onStartCommand() → RESUME_SESSION")
                sessionManager.resumeSession()
            }
            ACTION_STOP -> {
                Log.i(TAG, "onStartCommand() → STOP_SESSION")
                sessionManager.stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_COMPLETE -> {
                Log.i(TAG, "onStartCommand() → COMPLETE_SESSION (Alarm Triggered)")
                sessionManager.completeSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_OPEN -> {
                Log.i(TAG, "onStartCommand() → OPEN_APP")
                val openIntent = Intent().setClassName("com.jainam.studentos", "com.jainam.studentos.MainActivity")
                openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                startActivity(openIntent)
            }
            else -> {
                Log.i(TAG, "onStartCommand() → default START")
            }
        }
        return START_NOT_STICKY
    }

    private fun observeSession() {
        if (isObserving) return
        isObserving = true
        serviceScope.launch {
            try {
                sessionManager.status.collectLatest { status ->
                    try {
                        when (status.state) {
            FocusSessionState.ACTIVE -> {
                val notification = buildNotification(
                    status.secondsRemaining,
                    status.durationSeconds,
                    isPaused = false,
                    strict = status.strict
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        @Suppress("DEPRECATION")
                        startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
                    }
                }
            }
            FocusSessionState.PAUSED -> {
                val notification = buildNotification(
                    status.secondsRemaining,
                    status.durationSeconds,
                    isPaused = true,
                    strict = status.strict
                )
                notificationHelper.sendAlert(
                    AppConfig.SESSION_ALERTS_NOTIFICATION_ID,
                    "Focus Paused",
                    "Your focus session is paused."
                )
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                    } else {
                        @Suppress("DEPRECATION")
                        startForeground(AppConfig.FOREGROUND_SERVICE_NOTIFICATION_ID, notification)
                    }
                }
            }
            FocusSessionState.COMPLETED -> {
                                notificationHelper.sendAlert(
                                    AppConfig.SESSION_ALERTS_NOTIFICATION_ID,
                                    "Session Complete!",
                                    "Great focus session! You completed ${status.durationSeconds / 60} minutes."
                                )
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                            FocusSessionState.INACTIVE -> {
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error updating notification for state ${status.state}", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Fatal error in session observation", e)
                isObserving = false
            }
        }
    }

    private fun buildNotification(
        secondsRemaining: Long,
        durationSeconds: Long,
        isPaused: Boolean,
        strict: Boolean = false
    ): android.app.Notification {
        val openIntent = Intent(this, FocusForegroundService::class.java).apply {
            action = ACTION_OPEN
        }
        val openPendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_OPEN, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseIntent = Intent(this, FocusForegroundService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_PAUSE, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val resumeIntent = Intent(this, FocusForegroundService::class.java).apply {
            action = ACTION_RESUME
        }
        val resumePendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_RESUME, resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FocusForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, REQUEST_CODE_STOP, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remainingFormatted = formatTime(secondsRemaining)
        val elapsedMinutes = (durationSeconds - secondsRemaining) / 60
        val totalMinutes = durationSeconds / 60

        val contentText = if (isPaused) {
            "Paused - $remainingFormatted remaining"
        } else {
            "$remainingFormatted remaining (${elapsedMinutes}m / ${totalMinutes}m)"
        }

        val builder = NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
            .setContentTitle("Focus Shield")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(openPendingIntent)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        if (isPaused) {
            if (!strict) builder.addAction(0, "Resume", resumePendingIntent)
        } else {
            builder.addAction(0, "Pause", pausePendingIntent)
        }
        builder.addAction(0, "Stop", stopPendingIntent)

        return builder.build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() — FocusForegroundService shutting down")
        serviceScope.cancel()
        isObserving = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun formatTime(seconds: Long): String {
        val hrs = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hrs > 0) "%d:%02d:%02d".format(hrs, mins, secs)
        else "%02d:%02d".format(mins, secs)
    }
}

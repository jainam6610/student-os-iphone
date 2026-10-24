package com.jainam.studentos

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.PendingIntent
import android.os.Build
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.jainam.studentos.focusshield.FocusShieldBridge
import com.jainam.studentos.focusshield.FocusShieldManager
import com.jainam.studentos.focusshield.core.constants.AppConfig
import com.jainam.studentos.focusshield.core.notification.NotificationHelper

class DeepLockService : AccessibilityService() {

    companion object {
        private const val TAG = "[Accessibility] DeepLockService"
        private const val NOTIFICATION_ID = 9002

        @Volatile
        private var instance: DeepLockService? = null
        private val instanceLock = Any()

        @JvmStatic
        fun getInstance(): DeepLockService? = synchronized(instanceLock) { instance }

        @JvmStatic
        fun isServiceRunning(): Boolean = synchronized(instanceLock) { instance != null }

        @JvmStatic
        private fun setInstance(service: DeepLockService?) {
            synchronized(instanceLock) { instance = service }
        }
    }

    private var setupDone = false
    private lateinit var notificationHelper: NotificationHelper

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (setupDone) return
        setupDone = true

        val info = serviceInfo
        // TYPE_WINDOW_CONTENT_CHANGED is required for the website/content filters:
        // browsers only expose the address bar text through content-change events
        // (window-state-changed carries no text at all). Without it the adult /
        // blocked-website checks could never see a URL.
        // TYPE_VIEW_SCROLLED is required for the short-form (Shorts/Reels) limit:
        // scrolling the player to the next item is the only signal that the user
        // moved on, since the player is a fragment of the host activity.
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        // View IDs let the URL extractor find the browser's address-bar node.
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        serviceInfo = info

        Log.i(TAG, "onServiceConnected() — Accessibility service bound")
        setInstance(this)
        notificationHelper = NotificationHelper(this)
        notificationHelper.createNotificationChannels()

        val existing = FocusShieldBridge.getManager()
        val fsm = existing ?: FocusShieldManager(this)
        if (existing != null) {
            // Reuse the manager the plugin already created: a second instance
            // would start a second engine + poller and split brain the bridge.
            existing.attachAccessibilityCallbacks(
                selfPackageName = packageName,
                performGlobalAction = { action -> performGlobalAction(action) },
                getRootNode = { rootInActiveWindow }
            )
        } else {
            fsm.setup(
                selfPackageName = packageName,
                performGlobalAction = { action -> performGlobalAction(action) },
                // Lets the manager read the address bar for website blocking.
                getRootNode = { rootInActiveWindow }
            )
        }

        FocusShieldBridge.initialize(fsm) { action -> performGlobalAction(action) }

        fsm.addListener(sessionListener)

        startServiceNotification()
    }

    private val sessionListener = object : FocusShieldManager.Listener {
        override fun onForegroundAppChanged(packageName: String, appName: String) {
            synchronized(pluginCallbacks) {
                for (cb in pluginCallbacks) {
                    cb.onForegroundAppChanged(packageName, appName)
                }
            }
        }

        override fun onBlockedAppAttempt(packageName: String, appName: String, timestamp: Long) {
            synchronized(pluginCallbacks) {
                for (cb in pluginCallbacks) {
                    cb.onBlockedAppAttempt(packageName, appName)
                }
            }
        }

        override fun onSessionStateChanged(state: String) {
            updateSessionNotification(state)
            synchronized(pluginCallbacks) {
                for (cb in pluginCallbacks) {
                    cb.onSessionStateChanged(state)
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        FocusShieldBridge.getManager()?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        Log.i(TAG, "onDestroy() — Accessibility service shutting down")
        // Stop enforcement before the process can lose its bind: detector,
        // both pollers and the foreground notification.
        try {
            FocusShieldBridge.getManager()?.let { fsm ->
                fsm.removeListener(sessionListener)
                fsm.shutdown()
            }
        } catch (_: Exception) {}
        stopForegroundNotification()
        FocusShieldBridge.clear()
        setInstance(null)
        synchronized(pluginCallbacks) {
            pluginCallbacks.clear()
        }
        super.onDestroy()
    }

    private fun startServiceNotification() {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
            .setContentTitle("Focus Shield")
            .setContentText("Ready to protect your focus")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(pending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /** Re-attach this service as foreground (session running). */
    private fun startForegroundNotification(notification: android.app.Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundNotification() failed", e)
        }
    }

    /** Drop the foreground state as soon as no session is running. */
    private fun stopForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {}
        try {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.cancel(NOTIFICATION_ID)
        } catch (_: Exception) {}
    }

    private fun updateSessionNotification(state: String) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = when (state) {
            "active" -> NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
                .setContentTitle("Focus Session Active")
                .setContentText("Distractions are being blocked")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            "paused" -> NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
                .setContentTitle("Focus Session Paused")
                .setContentText("Tap to resume your session")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            "completed" -> NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
                .setContentTitle("Session Complete!")
                .setContentText("Great focus session")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            else -> NotificationCompat.Builder(this, AppConfig.NOTIFICATION_CHANNEL_ID_CORE)
                .setContentTitle("Focus Shield")
                .setContentText("Ready to protect your focus")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }

        when (state) {
            // A session is running (or paused mid-session): stay foreground.
            "active", "paused" -> startForegroundNotification(notification)
            // Ended / cancelled / idle: no reason to hold a foreground service.
            else -> stopForegroundNotification()
        }
    }

    interface PluginCallback {
        fun onForegroundAppChanged(packageName: String, appName: String)
        fun onBlockedAppAttempt(packageName: String, appName: String)
        fun onSessionStateChanged(state: String)
    }

    private val pluginCallbacks = ArrayList<PluginCallback>()

    fun addPluginCallback(cb: PluginCallback) {
        synchronized(pluginCallbacks) {
            if (!pluginCallbacks.contains(cb)) pluginCallbacks.add(cb)
        }
    }

    fun removePluginCallback(cb: PluginCallback) {
        synchronized(pluginCallbacks) {
            pluginCallbacks.remove(cb)
        }
    }
}

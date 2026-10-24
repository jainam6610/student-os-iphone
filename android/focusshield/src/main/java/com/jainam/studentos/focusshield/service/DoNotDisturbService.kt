package com.jainam.studentos.focusshield.service

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log

class DoNotDisturbService(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    @Volatile
    private var previousInterruptionFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL

    companion object {
        private const val TAG = "[DND]"
    }

    fun isDndPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            notificationManager.isNotificationPolicyAccessGranted
        } else {
            true
        }
    }

    fun enableDNDIfPermitted() {
        try {
            val granted = isDndPermissionGranted()
            Log.i(TAG, "enableDNDIfPermitted() granted=$granted")
            if (granted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    previousInterruptionFilter = notificationManager.currentInterruptionFilter
                    notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
                    Log.i(TAG, "enableDNDIfPermitted() — DND activated (was filter=$previousInterruptionFilter)")
                }
            } else {
                Log.w(TAG, "enableDNDIfPermitted() — permission not granted, skipping")
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableDNDIfPermitted() FAILED — ${e::class.simpleName}: ${e.message}", e)
        }
    }

    fun restoreDNDState() {
        try {
            val granted = isDndPermissionGranted()
            Log.i(TAG, "restoreDNDState() granted=$granted restoreToFilter=$previousInterruptionFilter")
            if (granted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    notificationManager.setInterruptionFilter(previousInterruptionFilter)
                    Log.i(TAG, "restoreDNDState() — DND restored to filter=$previousInterruptionFilter")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "restoreDNDState() FAILED — ${e::class.simpleName}: ${e.message}", e)
        }
    }
}

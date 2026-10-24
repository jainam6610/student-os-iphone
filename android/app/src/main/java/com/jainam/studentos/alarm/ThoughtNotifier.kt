package com.jainam.studentos.alarm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Gentle delivery for motivation reminders ("thoughts").
 * Deliberately separate from the alarm pipeline: silent notification with a
 * full-screen intent so the thought card appears on screen even when OEM
 * battery optimizations suppress heads-up banners. No looping audio, no
 * vibration, no full-screen alarm UI.
 */
object ThoughtNotifier {

    private const val TAG = "[ThoughtNotifier]"
    // v2: v1 was created as IMPORTANCE_DEFAULT; channel settings are immutable
    // once created, so heads-up requires a fresh channel id.
    private const val CHANNEL_ID = "thoughts_channel_v2"
    private const val PREFS_NAME = "student_os_thoughts"

    private const val KEY_TITLE = "title"
    private const val KEY_BODY = "body"
    private const val KEY_ENTITY_ID = "entity_id"
    private const val KEY_TS = "ts"
    private const val KEY_TOUCHED_PREFIX = "touched_"

    @JvmStatic
    fun show(context: Context, entityId: String, title: String, body: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(nm)

            savePending(context, entityId, title, body)

            val openIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_THOUGHT_OPEN
                putExtra(AlarmReceiver.EXTRA_ENTITY_ID, entityId)
            }
            val openPi = PendingIntent.getBroadcast(
                context,
                ("open$entityId").hashCode(),
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val deleteIntent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_THOUGHT_DISMISSED
                putExtra(AlarmReceiver.EXTRA_ENTITY_ID, entityId)
            }
            val deletePi = PendingIntent.getBroadcast(
                context,
                ("del$entityId").hashCode(),
                deleteIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            // No full-screen intent: thoughts are delivered as a normal
            // high-importance heads-up notification. The card only appears when
            // the user taps it (contentIntent) — no involuntary app takeover.

            val startFocusAction = NotificationCompat.Action.Builder(
                android.R.drawable.ic_media_play,
                "Start focus",
                openPi,
            ).build()

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(openPi)
                .addAction(startFocusAction)
                .setDeleteIntent(deletePi)
                .setAutoCancel(true)

            nm.notify(entityId.hashCode(), builder.build())
            Log.i(TAG, "Thought notification posted: $entityId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post thought notification", e)
        }
    }

    @JvmStatic
    fun cancel(context: Context, entityId: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(entityId.hashCode())
        } catch (_: Exception) {}
    }

    /** Touched = user tapped the notification or swiped it away. */
    @JvmStatic
    fun markTouched(context: Context, entityId: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_TOUCHED_PREFIX + entityId, true).apply()
    }

    @JvmStatic
    fun wasTouched(context: Context, entityId: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_TOUCHED_PREFIX + entityId, false)

    @JvmStatic
    fun savePending(context: Context, entityId: String, title: String, body: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_TITLE, title)
            .putString(KEY_BODY, body)
            .putString(KEY_ENTITY_ID, entityId)
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
    }

    @JvmStatic
    fun clearPending(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_TITLE).remove(KEY_BODY).remove(KEY_ENTITY_ID).remove(KEY_TS).apply()
    }

    private fun createChannel(nm: NotificationManager) {
        // IMPORTANCE_HIGH = heads-up banner pops onto the screen.
        // Channel carries no sound/vibration, so it stays a calm visual popup.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Motivation Thoughts",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Gentle motivational nudges"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}


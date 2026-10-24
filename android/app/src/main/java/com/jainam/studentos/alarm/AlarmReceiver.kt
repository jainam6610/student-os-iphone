package com.jainam.studentos.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "[AlarmReceiver]"
        const val EXTRA_ENTITY_ID = "alarm_entity_id"
        const val EXTRA_TYPE = "alarm_type"
        const val EXTRA_TITLE = "alarm_title"
        const val EXTRA_BODY = "alarm_body"
        const val EXTRA_SCHEDULE_AT_MS = "alarm_schedule_at_ms"
        const val EXTRA_IS_RECURRING = "alarm_is_recurring"
        const val EXTRA_TIME_STR = "alarm_time_str"
        const val EXTRA_SOUND = "alarm_sound"
        const val EXTRA_VIBRATION = "alarm_vibration"
        const val EXTRA_EXTRA_JSON = "alarm_extra_json"
        const val ACTION_THOUGHT_OPEN = "com.jainam.studentos.action.THOUGHT_OPEN"
        const val ACTION_THOUGHT_DISMISSED = "com.jainam.studentos.action.THOUGHT_DISMISSED"
        private const val RETRY_SUFFIX = "-retry"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            // Thought touch actions: user tapped open or swiped the notification away.
            when (intent.action) {
                ACTION_THOUGHT_OPEN -> {
                    val eid = intent.getStringExtra(EXTRA_ENTITY_ID) ?: return
                    Log.i(TAG, "Thought opened: $eid")
                    ThoughtNotifier.markTouched(context, eid)
                    try { AlarmScheduler(context).cancel(eid + RETRY_SUFFIX) } catch (_: Exception) {}
                    // Pending thought is intentionally KEPT here: the app reads it
                    // via getPendingThought() and clears it after showing the card.
                    launchMain(context)
                    return
                }
                ACTION_THOUGHT_DISMISSED -> {
                    val eid = intent.getStringExtra(EXTRA_ENTITY_ID) ?: return
                    Log.i(TAG, "Thought dismissed: $eid")
                    endThoughtChain(context, eid)
                    return
                }
            }

            val entityId = intent.getStringExtra(EXTRA_ENTITY_ID) ?: return
            Log.i(TAG, "Alarm received: $entityId")

            val title = intent.getStringExtra(EXTRA_TITLE) ?: "Alarm"
            val body = intent.getStringExtra(EXTRA_BODY) ?: ""
            val type = intent.getStringExtra(EXTRA_TYPE) ?: "habit"
            val sound = intent.getStringExtra(EXTRA_SOUND) ?: "default"
            val vibration = intent.getBooleanExtra(EXTRA_VIBRATION, true)
            val isRecurring = intent.getBooleanExtra(EXTRA_IS_RECURRING, false)
            val timeStr = intent.getStringExtra(EXTRA_TIME_STR) ?: ""
            val extraJson = intent.getStringExtra(EXTRA_EXTRA_JSON) ?: "{}"

            if (isRecurring && timeStr.isNotBlank()) {
                AlarmScheduler(context).scheduleNextDay(
                    entityId = entityId,
                    timeStr = timeStr,
                    type = type,
                    title = title,
                    body = body,
                    sound = sound,
                    vibration = vibration,
                    extraJson = extraJson,
                )
            } else {
                AlarmDataStore(context).removeAlarm(entityId)
            }

            // Motivation thoughts take the gentle path: silent notification,
            // one optional retry after 5 minutes if untouched, then stop.
            if (type == "motivation") {
                handleThought(context, entityId, title, body, extraJson)
                return
            }

            val serviceIntent = Intent(context, AlarmForegroundService::class.java).apply {
                putExtra(EXTRA_ENTITY_ID, entityId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
                putExtra(EXTRA_SOUND, sound)
                putExtra(EXTRA_VIBRATION, vibration)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }

            val extraHabitId = try {
                org.json.JSONObject(extraJson).optString("habitId", "")
            } catch (_: Exception) { ""
            }
            AlarmPlugin.fireAlarmEvent(entityId, title, body, extraHabitId)

            val activityIntent = Intent(context, AlarmFullScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_ENTITY_ID, entityId)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing alarm", e)
        }
    }

    private fun handleThought(context: Context, entityId: String, title: String, body: String, extraJson: String) {
        // Notifications only — no auto-launch, no retry duplicate. The thought
        // card surfaces only when the user taps the notification.
        ThoughtNotifier.show(context, entityId, title, body)
        Log.i(TAG, "Thought notification posted: $entityId")
    }

    /** Tapped or swiped away → cancel pending retry and clear in-app pending state. */
    private fun endThoughtChain(context: Context, entityId: String) {
        ThoughtNotifier.markTouched(context, entityId)
        try { AlarmScheduler(context).cancel(entityId + RETRY_SUFFIX) } catch (_: Exception) {}
        ThoughtNotifier.clearPending(context)
        ThoughtNotifier.cancel(context, entityId)
    }

    private fun launchMain(context: Context) {
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (intent != null) context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "launchMain failed", e)
        }
    }
}

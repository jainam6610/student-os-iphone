package com.jainam.studentos.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class AlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    companion object {
        private const val TAG = "[AlarmScheduler]"
        private const val REQUEST_CODE_BASE = 10000

        private const val EXTRA_ENTITY_ID = "alarm_entity_id"
        private const val EXTRA_TYPE = "alarm_type"
        private const val EXTRA_TITLE = "alarm_title"
        private const val EXTRA_BODY = "alarm_body"
        private const val EXTRA_SCHEDULE_AT_MS = "alarm_schedule_at_ms"
        private const val EXTRA_IS_RECURRING = "alarm_is_recurring"
        private const val EXTRA_TIME_STR = "alarm_time_str"
        private const val EXTRA_SOUND = "alarm_sound"
        private const val EXTRA_VIBRATION = "alarm_vibration"
        private const val EXTRA_EXTRA_JSON = "alarm_extra_json"

        // FNV-1a 32-bit hash — deterministic, collision-resistant for UUID-length strings
        private fun hashEntityId(entityId: String): Int {
            var hash = -2128831035 // 0x811c9dc5 as signed int (FNV offset basis)
            for (c in entityId) {
                hash = (hash xor c.code) * 16777619 // FNV prime
            }
            return hash and Int.MAX_VALUE
        }

        private fun oldRequestCode(entityId: String): Int =
            REQUEST_CODE_BASE + entityId.hashCode()

        private fun newRequestCode(entityId: String): Int =
            REQUEST_CODE_BASE + hashEntityId(entityId)
    }

    init {
        val store = AlarmDataStore(context)
        if (!store.isHashV2Migrated()) {
            Log.i(TAG, "Running hash v2 migration — cancelling all old alarms")
            // Cancel using old String.hashCode()-based request codes
            for (data in store.getAllAlarms()) {
                val rc = oldRequestCode(data.entityId)
                val intent = Intent(context, AlarmReceiver::class.java)
                val pi = PendingIntent.getBroadcast(
                    context, rc, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                alarmManager?.cancel(pi)
                pi.cancel()
            }
            store.clearAll()
            store.setHashV2Migrated()
            Log.i(TAG, "Hash v2 migration complete — all old alarms cancelled and cleared")
        }
    }

    fun schedule(data: AlarmData) {

        if (alarmManager == null) {
            Log.w(TAG, "AlarmManager unavailable")
            return
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ENTITY_ID, data.entityId)
            putExtra(EXTRA_TYPE, data.type)
            putExtra(EXTRA_TITLE, data.title)
            putExtra(EXTRA_BODY, data.body)
            putExtra(EXTRA_SCHEDULE_AT_MS, data.scheduleAtMs)
            putExtra(EXTRA_IS_RECURRING, data.isRecurring)
            putExtra(EXTRA_TIME_STR, data.timeStr)
            putExtra(EXTRA_SOUND, data.sound)
            putExtra(EXTRA_VIBRATION, data.vibration)
            putExtra(EXTRA_EXTRA_JSON, data.extraJson)
        }

        val requestCode = newRequestCode(data.entityId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)

        if (data.scheduleAtMs <= System.currentTimeMillis()) {
            Log.w(TAG, "schedule skipped — time in past for ${data.entityId}")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "Cannot schedule exact alarm — permission not granted. Falling back to setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, data.scheduleAtMs, pendingIntent)
                persistAlarm(data)
                return
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    data.scheduleAtMs,
                    pendingIntent,
                )
            } else {
                alarmManager.setExact(
                    AlarmManager.RTC_WAKEUP,
                    data.scheduleAtMs,
                    pendingIntent,
                )
            }
            Log.i(TAG, "Scheduled exact alarm: ${data.entityId} at ${data.scheduleAtMs}")
        } catch (e: Exception) {
            Log.e(TAG, "setExactAndAllowWhileIdle failed: ${e.message}. Falling back to setAndAllowWhileIdle")
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, data.scheduleAtMs, pendingIntent)
        }

        persistAlarm(data)
    }

    fun cancel(entityId: String) {
        if (alarmManager == null) return

        val requestCode = newRequestCode(entityId)
        val intent = Intent(context, AlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, flags)
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()

        AlarmDataStore(context).removeAlarm(entityId)
        Log.i(TAG, "Cancelled alarm: $entityId")
    }

    fun cancelAll() {
        val store = AlarmDataStore(context)
        val all = store.getAllAlarms()
        for (data in all) cancel(data.entityId)
        store.clearAll()
    }

    private fun persistAlarm(data: AlarmData) {
        AlarmDataStore(context).saveAlarm(data)
    }

    fun scheduleNextDay(entityId: String, timeStr: String, type: String, title: String, body: String, sound: String, vibration: Boolean, extraJson: String) {
        val now = System.currentTimeMillis()
        val parts = timeStr.split(":")
        if (parts.size != 2) {
            return
        }
        val hour = parts[0].toIntOrNull() ?: return
        val minute = parts[1].toIntOrNull() ?: return

        val extra = try { org.json.JSONObject(extraJson) } catch (_: Exception) { org.json.JSONObject() }
        val repeatType = extra.optString("repeatType", "daily")
        val repeatDaysJson = extra.optJSONArray("repeatDays")
        val repeatDays = mutableSetOf<Int>()
        if (repeatDaysJson != null) {
            for (i in 0 until repeatDaysJson.length()) {
                val day = repeatDaysJson.optInt(i, -1)
                if (day in 0..31) repeatDays.add(day)
            }
        }
        val repeatInterval = extra.optInt("repeatInterval", 1).coerceAtLeast(1)
        val createdAtMs = extra.optLong("createdAtMs", now)

        // Recur on the habit's configured dates rather than every day. Calendar
        // uses the device timezone, matching the HH:mm time selected in the UI.
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }
        var found = false
        for (daysAhead in 1..370) {
            val candidate = (cal.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.DAY_OF_YEAR, daysAhead)
                set(java.util.Calendar.HOUR_OF_DAY, hour)
                set(java.util.Calendar.MINUTE, minute)
            }
            val jsDay = (candidate.get(java.util.Calendar.DAY_OF_WEEK) - 1) // JS Sunday=0
            val due = when (repeatType) {
                "weekdays" -> jsDay in 1..5
                "weekends" -> jsDay == 0 || jsDay == 6
                "weekly" -> if (repeatDays.isEmpty()) jsDay == 1 else repeatDays.contains(jsDay)
                "monthly" -> repeatDays.isEmpty() || repeatDays.contains(candidate.get(java.util.Calendar.DAY_OF_MONTH))
                "custom" -> {
                    val created = java.util.Calendar.getInstance().apply { timeInMillis = createdAtMs }
                    val utc = java.util.TimeZone.getTimeZone("UTC")
                    val candidateDay = java.util.Calendar.getInstance(utc).apply {
                        set(candidate.get(java.util.Calendar.YEAR), candidate.get(java.util.Calendar.MONTH), candidate.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis / 86_400_000L
                    val createdDay = java.util.Calendar.getInstance(utc).apply {
                        set(created.get(java.util.Calendar.YEAR), created.get(java.util.Calendar.MONTH), created.get(java.util.Calendar.DAY_OF_MONTH), 0, 0, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis / 86_400_000L
                    (candidateDay - createdDay) % repeatInterval == 0L
                }
                else -> true
            }
            if (due) {
                cal.timeInMillis = candidate.timeInMillis
                found = true
                break
            }
        }
        if (!found) return

        val nextAtMs = cal.timeInMillis
        val data = AlarmData(
            entityId = entityId,
            type = type,
            title = title,
            body = body,
            scheduleAtMs = nextAtMs,
            isRecurring = true,
            timeStr = timeStr,
            sound = sound,
            vibration = vibration,
            extraJson = extraJson,
        )
        schedule(data)
    }
}

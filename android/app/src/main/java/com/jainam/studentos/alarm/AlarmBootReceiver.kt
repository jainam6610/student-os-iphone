package com.jainam.studentos.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class AlarmBootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "[AlarmBootReceiver]"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        try {
            if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

            Log.i(TAG, "Boot completed — rescheduling alarms")

            val store = AlarmDataStore(context)
            val scheduler = AlarmScheduler(context)
            val allAlarms = store.getAllAlarms()

            if (allAlarms.isEmpty()) {
                Log.i(TAG, "No alarms to reschedule")
                return
            }

            val now = System.currentTimeMillis()
            val missedWindow = 30 * 60 * 1000L

            for (data in allAlarms) {
                if (data.isRecurring && data.timeStr.isNotBlank()) {
                    val parts = data.timeStr.split(":")
                    val todayMs = if (parts.size == 2) {
                        val h = parts[0].toIntOrNull() ?: -1
                        val m = parts[1].toIntOrNull() ?: -1
                        if (h in 0..23 && m in 0..59) {
                            java.util.Calendar.getInstance().apply {
                                set(java.util.Calendar.HOUR_OF_DAY, h)
                                set(java.util.Calendar.MINUTE, m)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                        } else -1L
                    } else -1L
                    if (todayMs > 0 && now - todayMs in 1 until missedWindow) {
                        val fireData = AlarmData(
                            entityId = data.entityId, type = data.type, title = data.title,
                            body = data.body, scheduleAtMs = now + 2000L,
                            isRecurring = true, timeStr = data.timeStr,
                            sound = data.sound, vibration = data.vibration, extraJson = data.extraJson
                        )
                        scheduler.schedule(fireData)
                        Log.i(TAG, "Firing missed recurring alarm: ${data.entityId}")
                    } else {
                        scheduler.scheduleNextDay(
                            entityId = data.entityId,
                            timeStr = data.timeStr,
                            type = data.type,
                            title = data.title,
                            body = data.body,
                            sound = data.sound,
                            vibration = data.vibration,
                            extraJson = data.extraJson,
                        )
                        Log.i(TAG, "Rescheduled recurring alarm: ${data.entityId} at ${data.timeStr}")
                    }
                } else if (data.scheduleAtMs > now) {
                    scheduler.schedule(data)
                    Log.i(TAG, "Rescheduled one-time alarm: ${data.entityId}")
                } else {
                    store.removeAlarm(data.entityId)
                    Log.i(TAG, "Removed expired alarm: ${data.entityId}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Boot reschedule error", e)
        }
    }
}

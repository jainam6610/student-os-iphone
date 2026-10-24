package com.jainam.studentos.alarm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class AlarmData(
    val entityId: String,
    val type: String,
    val title: String,
    val body: String,
    val scheduleAtMs: Long,
    val isRecurring: Boolean,
    val timeStr: String,
    val sound: String,
    val vibration: Boolean,
    val extraJson: String,
)

class AlarmDataStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveAlarm(data: AlarmData) {
        val all = getAllAlarmsInternal().toMutableList()
        val existing = all.indexOfFirst { it.entityId == data.entityId }
        if (existing >= 0) all[existing] = data else all.add(data)
        prefs.edit().putString(KEY_ALARMS, serializeList(all)).apply()
    }

    fun removeAlarm(entityId: String) {
        val all = getAllAlarmsInternal().filter { it.entityId != entityId }
        prefs.edit().putString(KEY_ALARMS, serializeList(all)).apply()
    }

    fun getAllAlarms(): List<AlarmData> = getAllAlarmsInternal()

    fun clearAll() {
        prefs.edit().remove(KEY_ALARMS).apply()
    }

    private fun getAllAlarmsInternal(): List<AlarmData> {
        val json = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { parseAlarm(arr.getJSONObject(it)) }
        } catch (e: JSONException) {
            Log.e(TAG, "Corrupt alarm storage detected, clearing. Error: ${e.message}")
            prefs.edit().remove(KEY_ALARMS).apply()
            emptyList()
        }
    }

    private fun serializeList(list: List<AlarmData>): String {
        val arr = JSONArray()
        for (item in list) arr.put(serializeAlarm(item))
        return arr.toString()
    }

    private fun serializeAlarm(data: AlarmData): JSONObject = JSONObject().apply {
        put("entityId", data.entityId)
        put("type", data.type)
        put("title", data.title)
        put("body", data.body)
        put("scheduleAtMs", data.scheduleAtMs)
        put("isRecurring", data.isRecurring)
        put("timeStr", data.timeStr)
        put("sound", data.sound)
        put("vibration", data.vibration)
        put("extraJson", data.extraJson)
    }

    private fun parseAlarm(obj: JSONObject): AlarmData = AlarmData(
        entityId = obj.getString("entityId"),
        type = obj.getString("type"),
        title = obj.getString("title"),
        body = obj.getString("body"),
        scheduleAtMs = obj.getLong("scheduleAtMs"),
        isRecurring = obj.optBoolean("isRecurring", false),
        timeStr = obj.optString("timeStr", ""),
        sound = obj.optString("sound", "default"),
        vibration = obj.optBoolean("vibration", true),
        extraJson = obj.optString("extraJson", "{}"),
    )

    fun isHashV2Migrated(): Boolean = prefs.getBoolean(KEY_HASH_V2_MIGRATED, false)

    fun setHashV2Migrated() = prefs.edit().putBoolean(KEY_HASH_V2_MIGRATED, true).apply()

    companion object {
        private const val TAG = "[AlarmDataStore]"
        private const val PREFS_NAME = "student_os_alarms"
        private const val KEY_ALARMS = "scheduled_alarms"
        private const val KEY_HASH_V2_MIGRATED = "hash_v2_migrated"
    }
}

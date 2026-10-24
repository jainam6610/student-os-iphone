package com.jainam.studentos.focusshield.feature.blocking

import android.util.Log
import com.jainam.studentos.focusshield.data.DistractionDao
import com.jainam.studentos.focusshield.data.DistractionLog
import kotlinx.coroutines.flow.Flow

class DistractionTracker(
    private val distractionDao: DistractionDao
) {
    companion object {
        private const val TAG = "[Room] DistractionTracker"
    }

    suspend fun logDistraction(
        packageName: String,
        appName: String,
        sessionId: String?,
        blockingMode: String = "STANDARD",
        timeRemainingSeconds: Long = 0
    ) {
        val now = System.currentTimeMillis()
        val dayStart = getDayStart(now)
        Log.i(TAG, "logDistraction() pkg=$packageName app=$appName sessionId=$sessionId mode=$blockingMode remaining=${timeRemainingSeconds}s")
        val log = DistractionLog(
            packageName = packageName,
            appName = appName,
            timestamp = now,
            sessionId = sessionId,
            attempts = 1,
            continueAnywayUsed = false,
            blockingMode = blockingMode,
            timeRemainingSeconds = timeRemainingSeconds,
            dayTimestamp = dayStart
        )
        distractionDao.insertLog(log)
        Log.i(TAG, "logDistraction() insert complete for $packageName")
    }

    suspend fun logBypass(
        packageName: String,
        appName: String,
        sessionId: String?,
        blockingMode: String = "STANDARD",
        timeRemainingSeconds: Long = 0
    ) {
        val now = System.currentTimeMillis()
        val dayStart = getDayStart(now)
        Log.i(TAG, "logBypass() pkg=$packageName app=$appName sessionId=$sessionId")
        val log = DistractionLog(
            packageName = packageName,
            appName = appName,
            timestamp = now,
            sessionId = sessionId,
            attempts = 1,
            continueAnywayUsed = true,
            blockingMode = blockingMode,
            timeRemainingSeconds = timeRemainingSeconds,
            dayTimestamp = dayStart
        )
        distractionDao.insertLog(log)
        Log.i(TAG, "logBypass() insert complete for $packageName")
    }

    suspend fun getDistractionCountSince(startTime: Long): Int {
        return distractionDao.getDistractionCountSince(startTime)
    }

    suspend fun getDailyDistractionCount(): Int {
        val now = System.currentTimeMillis()
        val dayStart = getDayStart(now)
        val dayEnd = dayStart + 86_400_000L
        return distractionDao.getDistractionCountForDay(dayStart, dayEnd)
    }

    suspend fun getDailyBypassCount(): Int {
        val now = System.currentTimeMillis()
        val dayStart = getDayStart(now)
        val dayEnd = dayStart + 86_400_000L
        return distractionDao.getBypassCountForDay(dayStart, dayEnd)
    }

    suspend fun getRecentlyBlockedPackages(limit: Int = 20): List<String> {
        val now = System.currentTimeMillis()
        val dayStart = getDayStart(now)
        val dayEnd = dayStart + 86_400_000L
        return distractionDao.getRecentlyBlockedPackages(dayStart, dayEnd, limit)
    }

    fun getAllLogsFlow(): Flow<List<DistractionLog>> {
        return distractionDao.getAllLogsFlow()
    }

    private fun getDayStart(timestamp: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = timestamp
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

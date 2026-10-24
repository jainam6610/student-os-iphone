package com.jainam.studentos.focusshield.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DistractionDao {
    @Query("SELECT * FROM distraction_logs ORDER BY timestamp DESC")
    fun getAllLogsFlow(): Flow<List<DistractionLog>>

    @Query("SELECT * FROM distraction_logs ORDER BY timestamp DESC")
    suspend fun getAllLogs(): List<DistractionLog>

    @Insert
    suspend fun insertLog(log: DistractionLog)

    @Query("SELECT COUNT(*) FROM distraction_logs WHERE timestamp >= :startTime")
    suspend fun getDistractionCountSince(startTime: Long): Int

    @Query("SELECT COUNT(*) FROM distraction_logs WHERE dayTimestamp >= :dayStart AND dayTimestamp < :dayEnd")
    suspend fun getDistractionCountForDay(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT COUNT(*) FROM distraction_logs WHERE dayTimestamp >= :dayStart AND dayTimestamp < :dayEnd AND continueAnywayUsed = 1")
    suspend fun getBypassCountForDay(dayStart: Long, dayEnd: Long): Int

    @Query("SELECT packageName, appName, COUNT(*) as count, SUM(CASE WHEN continueAnywayUsed THEN 1 ELSE 0 END) as continueCount FROM distraction_logs GROUP BY packageName, appName ORDER BY count DESC LIMIT :limit")
    suspend fun getMostDistractingApps(limit: Int): List<AppDistractionStat>

    @Query("SELECT packageName, appName, COUNT(*) as count, SUM(CASE WHEN continueAnywayUsed THEN 1 ELSE 0 END) as continueCount FROM distraction_logs WHERE dayTimestamp >= :dayStart AND dayTimestamp < :dayEnd GROUP BY packageName, appName ORDER BY count DESC LIMIT :limit")
    suspend fun getMostDistractingAppsForDay(dayStart: Long, dayEnd: Long, limit: Int): List<AppDistractionStat>

    @Query("SELECT (timestamp / 3600000) % 24 as hour, COUNT(*) as count FROM distraction_logs GROUP BY hour ORDER BY count DESC")
    suspend fun getDistractionsByHour(): List<HourDistractionStat>

    @Query("SELECT DISTINCT packageName FROM distraction_logs WHERE dayTimestamp >= :dayStart AND dayTimestamp < :dayEnd ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentlyBlockedPackages(dayStart: Long, dayEnd: Long, limit: Int): List<String>

    @Query("SELECT SUM(attempts) FROM distraction_logs WHERE sessionId = :sessionId")
    suspend fun getTotalAttemptsForSession(sessionId: String): Int
}

data class AppDistractionStat(
    val packageName: String,
    val appName: String,
    val count: Int,
    val continueCount: Int
)

data class HourDistractionStat(
    val hour: Long,
    val count: Int
)

package com.jainam.studentos.focusshield.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "distraction_logs")
data class DistractionLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val appName: String,
    val timestamp: Long,
    val sessionId: String?,
    val attempts: Int,
    val continueAnywayUsed: Boolean,
    val blockingMode: String = "STANDARD",
    val timeRemainingSeconds: Long = 0,
    val dayTimestamp: Long = timestamp
)

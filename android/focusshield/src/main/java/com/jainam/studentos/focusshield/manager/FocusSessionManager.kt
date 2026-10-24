package com.jainam.studentos.focusshield.manager

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jainam.studentos.focusshield.data.datastore.dataStore
import com.jainam.studentos.focusshield.service.DoNotDisturbService
import com.jainam.studentos.focusshield.service.FocusForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

enum class FocusSessionState {
    INACTIVE,
    ACTIVE,
    PAUSED,
    COMPLETED
}

data class SessionStatus(
    val sessionId: String?,
    val state: FocusSessionState,
    val plannedDurationMillis: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val pauseStartedAtMillis: Long?,
    val totalPausedDurationMillis: Long,
    val monotonicStartMillis: Long,
    val version: Int,
    val streakCount: Int,
    val strict: Boolean = false
) {
    val secondsRemaining: Long get() {
        if (state == FocusSessionState.INACTIVE || state == FocusSessionState.COMPLETED) return 0L
        val remainingMs = when (state) {
            FocusSessionState.ACTIVE -> endTimeMillis - System.currentTimeMillis()
            FocusSessionState.PAUSED -> if (pauseStartedAtMillis != null) endTimeMillis - pauseStartedAtMillis else 0L
            else -> 0L
        }
        return (remainingMs / 1000).coerceAtLeast(0)
    }

    val durationSeconds: Long get() = plannedDurationMillis / 1000

    fun actualDurationMs(): Long {
        if (startTimeMillis == 0L) return 0L
        return (endTimeMillis - startTimeMillis) - totalPausedDurationMillis
    }
}

class FocusSessionManager private constructor(
    private val context: Context,
    private val dndService: DoNotDisturbService,
    private val startForegroundOnSession: Boolean = true
) {
    private val scope = CoroutineScope(Dispatchers.Default)
    private val tickerJob = AtomicReference<Job?>(null)
    private var saveCounter = 0

    @Volatile
    private var restoreReady = false

    private var persistedVersion: Int = 0

    @Volatile
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var pendingDurationMinutes: Int? = null

    @Volatile
    private var pendingStrict: Boolean = false

    private val _status = MutableStateFlow(
        SessionStatus(
            sessionId = null,
            state = FocusSessionState.INACTIVE,
            plannedDurationMillis = 0,
            startTimeMillis = 0,
            endTimeMillis = 0,
            pauseStartedAtMillis = null,
            totalPausedDurationMillis = 0,
            monotonicStartMillis = 0,
            version = 0,
            streakCount = 0
        )
    )
    val status: StateFlow<SessionStatus> = _status.asStateFlow()

    init {
        scope.launch {
            restoreSessionState()
            restoreReady = true
            pendingDurationMinutes?.let { dur ->
                pendingDurationMinutes = null
                val strict = pendingStrict
                pendingStrict = false
                Log.i(TAG, "replaying deferred session of ${dur}min (restore complete)")
                startSession(dur, strict)
            }
        }
    }

    companion object {
        private const val TAG = "[FocusSession] FocusSessionManager"
        private val KEY_SESSION_ID = stringPreferencesKey("session_id")
        private val KEY_STATE = stringPreferencesKey("session_state")
        private val KEY_PLANNED_DURATION = longPreferencesKey("planned_duration_millis")
        private val KEY_START_TIME = longPreferencesKey("start_time_millis")
        private val KEY_END_TIME = longPreferencesKey("end_time_millis")
        private val KEY_PAUSE_STARTED_AT = longPreferencesKey("pause_started_at_millis")
        private val KEY_TOTAL_PAUSED = longPreferencesKey("total_paused_duration_millis")
        private val KEY_MONOTONIC_START = longPreferencesKey("monotonic_start_millis")
        private val KEY_VERSION = longPreferencesKey("version")
        private val KEY_STREAK = stringPreferencesKey("session_streak_count")
        private val KEY_STRICT = booleanPreferencesKey("session_strict")
        private val KEY_PENDING_COMPLETION = stringPreferencesKey("pending_completion_json")
        private const val DATASTORE_SAVE_INTERVAL = 30

        @Volatile
        private var INSTANCE: FocusSessionManager? = null

        private var onSessionCompleteCallbacks: MutableList<suspend () -> Unit> = mutableListOf()

        fun addOnSessionCompleteCallback(callback: suspend () -> Unit) {
            synchronized(onSessionCompleteCallbacks) {
                onSessionCompleteCallbacks.add(callback)
            }
        }

        fun removeOnSessionCompleteCallback(callback: suspend () -> Unit) {
            synchronized(onSessionCompleteCallbacks) {
                onSessionCompleteCallbacks.remove(callback)
            }
        }

        @Deprecated("Use addOnSessionCompleteCallback instead", ReplaceWith("addOnSessionCompleteCallback(callback)"))
        fun setOnSessionCompleteCallback(callback: suspend () -> Unit) {
            synchronized(onSessionCompleteCallbacks) {
                onSessionCompleteCallbacks.clear()
                onSessionCompleteCallbacks.add(callback)
            }
        }

        fun getInstance(context: Context, dndService: DoNotDisturbService, startForeground: Boolean = true): FocusSessionManager {
            return INSTANCE ?: synchronized(this) {
                val instance = FocusSessionManager(context.applicationContext, dndService, startForeground)
                INSTANCE = instance
                instance
            }
        }
    }

    @Synchronized
    fun startSession(durationMinutes: Int, strict: Boolean = false) {
        val durationMs = durationMinutes * 60_000L
        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val monotonicNow = SystemClock.elapsedRealtime()

        Log.i(TAG, "[FOCUS_INSTRUMENT] startSession id=$sessionId duration=${durationMinutes}min (${durationMs}ms) restoreReady=$restoreReady strict=$strict")

        if (!restoreReady) {
            Log.w(TAG, "[FOCUS_INSTRUMENT] startSession - restore not yet ready, deferring ${durationMinutes}min")
            pendingDurationMinutes = durationMinutes
            pendingStrict = strict
            return
        }

        val currentState = _status.value.state
        if (currentState == FocusSessionState.ACTIVE || currentState == FocusSessionState.PAUSED) {
            Log.w(TAG, "[FOCUS_INSTRUMENT] startSession SKIPPED — session already in state $currentState")
            return
        }

        _status.update { current ->
            SessionStatus(
                sessionId = sessionId,
                state = FocusSessionState.ACTIVE,
                plannedDurationMillis = durationMs,
                startTimeMillis = now,
                endTimeMillis = now + durationMs,
                pauseStartedAtMillis = null,
                totalPausedDurationMillis = 0,
                monotonicStartMillis = monotonicNow,
                version = 1,
                streakCount = current.streakCount,
                strict = strict
            )
        }
        persistedVersion = 1

        saveSessionState()
        dndService.enableDNDIfPermitted()
        acquireWakeLock()
        startTicker()
        startForegroundService()
        scheduleCompletionAlarm(now + durationMs)
    }

    @Synchronized
    fun pauseSession() {
        val current = _status.value
        Log.i(TAG, "[FOCUS_INSTRUMENT] pauseSession() currentState=${current.state} remaining=${current.secondsRemaining}s")
        if (current.state == FocusSessionState.ACTIVE) {
            tickerJob.get()?.cancel()
            _status.update {
                it.copy(
                    state = FocusSessionState.PAUSED,
                    pauseStartedAtMillis = System.currentTimeMillis(),
                    version = it.version + 1
                )
            }
            persistedVersion = _status.value.version
            saveSessionState()
            dndService.restoreDNDState()
            cancelCompletionAlarm()
            releaseWakeLock()
            Log.i(TAG, "[FOCUS_INSTRUMENT] pauseSession() complete — remaining=${_status.value.secondsRemaining}s")
        } else {
            Log.w(TAG, "[FOCUS_INSTRUMENT] pauseSession() SKIPPED — not in ACTIVE state")
        }
    }

    @Synchronized
    fun resumeSession() {
        val current = _status.value
        Log.i(TAG, "[FOCUS_INSTRUMENT] resumeSession() currentState=${current.state} remaining=${current.secondsRemaining}s")
        if (current.state == FocusSessionState.PAUSED && current.pauseStartedAtMillis != null) {
            val now = System.currentTimeMillis()
            val pausedDuration = now - current.pauseStartedAtMillis
            val newEndTime = current.endTimeMillis + pausedDuration
            val newTotalPaused = current.totalPausedDurationMillis + pausedDuration

            _status.update {
                it.copy(
                    state = FocusSessionState.ACTIVE,
                    endTimeMillis = newEndTime,
                    pauseStartedAtMillis = null,
                    totalPausedDurationMillis = newTotalPaused,
                    version = it.version + 1
                )
            }
            persistedVersion = _status.value.version
            saveSessionState()
            dndService.enableDNDIfPermitted()
            acquireWakeLock()
            startTicker()
            scheduleCompletionAlarm(newEndTime)
            Log.i(TAG, "[FOCUS_INSTRUMENT] resumeSession() complete — paused=${pausedDuration}ms newEndTime=$newEndTime")
        } else {
            Log.w(TAG, "[FOCUS_INSTRUMENT] resumeSession() SKIPPED — not in PAUSED state")
        }
    }

    @Synchronized
    fun stopSession() {
        val previousState = _status.value.state
        val prevId = _status.value.sessionId
        Log.i(TAG, "[FOCUS_INSTRUMENT] stopSession previousState=$previousState sessionId=$prevId")
        tickerJob.get()?.cancel()
        _status.update { current ->
            SessionStatus(
                sessionId = null,
                state = FocusSessionState.INACTIVE,
                plannedDurationMillis = 0,
                startTimeMillis = 0,
                endTimeMillis = 0,
                pauseStartedAtMillis = null,
                totalPausedDurationMillis = 0,
                monotonicStartMillis = 0,
                version = 0,
                streakCount = current.streakCount
            )
        }
        persistedVersion = 0
        pendingDurationMinutes = null
        saveSessionState()
        dndService.restoreDNDState()
        cancelCompletionAlarm()
        releaseWakeLock()
        Log.i(TAG, "[FOCUS_INSTRUMENT] stopSession complete — state cleared to INACTIVE")
    }

    @Synchronized
    fun completeSession() {
        // Idempotency guard: if we're already COMPLETED or INACTIVE, do nothing.
        // This prevents double-completion when both the AlarmManager alarm and the
        // coroutine ticker fire near-simultaneously (e.g., on Doze wakeup).
        val currentState = _status.value.state
        if (currentState == FocusSessionState.COMPLETED || currentState == FocusSessionState.INACTIVE) {
            Log.w(TAG, "[FOCUS_INSTRUMENT] completeSession() SKIPPED — already in state=$currentState")
            return
        }
        val finalId = _status.value.sessionId
        Log.i(TAG, "[FOCUS_INSTRUMENT] completeSession() — timer expired, marking COMPLETED id=$finalId")
        tickerJob.get()?.cancel()
        _status.update { current ->
            current.copy(
                state = FocusSessionState.COMPLETED,
                pauseStartedAtMillis = null,
                version = current.version + 1,
                streakCount = current.streakCount + 1
            )
        }
        persistedVersion = _status.value.version
        dndService.restoreDNDState()
        saveSessionState()
        cancelCompletionAlarm()
        releaseWakeLock()
        Log.i(TAG, "[FOCUS_INSTRUMENT] completeSession() id=$finalId actualMs=${_status.value.actualDurationMs()}")
        scope.launch {
            val cbs: List<suspend () -> Unit>
            synchronized(onSessionCompleteCallbacks) { cbs = onSessionCompleteCallbacks.toList() }
            for (cb in cbs) {
                try { cb() } catch (e: Exception) { Log.e(TAG, "onSessionCompleteCallback error", e) }
            }
        }
        stopForegroundService()
    }

    fun getSessionStateSnapshot(): Map<String, Any?> {
        val s = _status.value
        return mapOf(
            "sessionId" to s.sessionId,
            "status" to s.state.name.lowercase(),
            "startTime" to s.startTimeMillis,
            "endTime" to s.endTimeMillis,
            "plannedDuration" to s.plannedDurationMillis,
            "pauseStartedAt" to s.pauseStartedAtMillis,
            "totalPausedDuration" to s.totalPausedDurationMillis,
            "version" to s.version,
            "streak" to s.streakCount,
            "strict" to s.strict,
            "remainingMs" to (s.secondsRemaining * 1000).coerceAtLeast(0),
            "actualDurationMs" to s.actualDurationMs()
        )
    }

    suspend fun savePendingCompletion(
        sessionId: String,
        actualDurationMs: Long,
        plannedDuration: Long,
        streak: Int,
        startTime: Long,
        endTime: Long
    ) {
        try {
            val json = org.json.JSONObject().apply {
                put("sessionId", sessionId)
                put("actualDurationMs", actualDurationMs)
                put("plannedDuration", plannedDuration)
                put("streak", streak)
                put("startTime", startTime)
                put("endTime", endTime)
            }.toString()
            context.dataStore.edit { prefs ->
                prefs[KEY_PENDING_COMPLETION] = json
            }
            Log.i(TAG, "[FOCUS_INSTRUMENT] savePendingCompletion() saved for sessionId=$sessionId")
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] savePendingCompletion() failed", e)
        }
    }

    suspend fun consumePendingCompletion(): Map<String, Any>? {
        return try {
            val prefs = context.dataStore.data.first()
            val json = prefs[KEY_PENDING_COMPLETION] ?: return null
            context.dataStore.edit { it.remove(KEY_PENDING_COMPLETION) }
            val obj = org.json.JSONObject(json)
            mapOf(
                "sessionId" to obj.optString("sessionId", ""),
                "actualDurationMs" to obj.optLong("actualDurationMs", 0L),
                "plannedDuration" to obj.optLong("plannedDuration", 0L),
                "streak" to obj.optInt("streak", 0),
                "startTime" to obj.optLong("startTime", 0L),
                "endTime" to obj.optLong("endTime", 0L)
            )
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] consumePendingCompletion() failed", e)
            null
        }
    }

    private fun computeRemainingMs(status: SessionStatus): Long {
        return (status.secondsRemaining * 1000).coerceAtLeast(0)
    }

    private fun detectClockDrift(): Boolean {
        val current = _status.value
        if (current.startTimeMillis == 0L || current.monotonicStartMillis == 0L) return false
        if (current.state != FocusSessionState.ACTIVE && current.state != FocusSessionState.PAUSED) return false

        val wallElapsed = System.currentTimeMillis() - current.startTimeMillis
        val monoElapsed = SystemClock.elapsedRealtime() - current.monotonicStartMillis
        val drift = wallElapsed - monoElapsed

        if (abs(drift) > 2000) {
            Log.w(TAG, "[FOCUS_INSTRUMENT] CLOCK_DRIFT wallElapsed=${wallElapsed}ms monoElapsed=${monoElapsed}ms drift=${drift}ms")
            val expectedRemaining = current.plannedDurationMillis + current.totalPausedDurationMillis - monoElapsed
            val correctedEndTime = System.currentTimeMillis() + expectedRemaining.coerceAtLeast(0)
            _status.update {
                it.copy(
                    endTimeMillis = correctedEndTime,
                    version = it.version + 1
                )
            }
            persistedVersion = _status.value.version
            saveSessionState()
            return true
        }
        return false
    }

    private fun startTicker() {
        tickerJob.getAndSet(scope.launch {
            var tickIndex = 0L
            while (_status.value.state == FocusSessionState.ACTIVE) {
                try {
                    detectClockDrift()

                    val remainingMs: Long = computeRemainingMs(_status.value)
                    val tickRemaining = (remainingMs / 1000).coerceAtLeast(0)

                    if (tickIndex % 5 == 0L) {
                        Log.i(TAG, "[FOCUS_INSTRUMENT] tick tick=$tickIndex remaining=${tickRemaining}s sessionId=${_status.value.sessionId}")
                    }

                    if (remainingMs <= 0) {
                        Log.i(TAG, "[FOCUS_INSTRUMENT] tick COMPLETED naturally sessionId=${_status.value.sessionId}")
                        completeSession()
                        break
                    }

                    saveCounter++
                    if (saveCounter >= 6) { // every 6 ticks = every 30s
                        saveSessionState()
                        saveCounter = 0
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[FOCUS_INSTRUMENT] tick error", e)
                }
                tickIndex++
                delay(5000)
            }
            Log.d(TAG, "[FOCUS_INSTRUMENT] ticker loop exited (state=${_status.value.state})")
        })?.cancel()
    }

    private fun saveSessionState() {
        val current = _status.value
        scope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            try {
                context.dataStore.edit { prefs ->
                    prefs[KEY_SESSION_ID] = current.sessionId ?: ""
                    prefs[KEY_STATE] = current.state.name
                    prefs[KEY_PLANNED_DURATION] = current.plannedDurationMillis
                    prefs[KEY_START_TIME] = current.startTimeMillis
                    prefs[KEY_END_TIME] = current.endTimeMillis
                    prefs[KEY_PAUSE_STARTED_AT] = current.pauseStartedAtMillis ?: 0L
                    prefs[KEY_TOTAL_PAUSED] = current.totalPausedDurationMillis
                    prefs[KEY_MONOTONIC_START] = current.monotonicStartMillis
                    prefs[KEY_VERSION] = current.version.toLong()
                    prefs[KEY_STREAK] = current.streakCount.toString()
        prefs[KEY_STRICT] = current.strict
                }
            } catch (e: Exception) {
                Log.e(TAG, "[FOCUS_INSTRUMENT] Error saving session state", e)
            }
        }
    }

    private fun startForegroundService() {
        if (!startForegroundOnSession) {
            Log.d(TAG, "startForegroundService() SKIPPED — delegated to host service")
            return
        }
        val serviceIntent = Intent(context, FocusForegroundService::class.java)
        Log.i(TAG, "startForegroundService() launching FocusForegroundService...")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(serviceIntent)
            }
            Log.i(TAG, "startForegroundService() OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundService() FAILED — ${e::class.simpleName}: ${e.message}", e)
        }
    }

    private fun stopForegroundService() {
        try {
            val intent = Intent(context, FocusForegroundService::class.java)
            context.stopService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "stopForegroundService() error", e)
        }
    }

    suspend fun restoreSessionState() {
        try {
            val prefs = context.dataStore.data.first()
            val sessionId = prefs[KEY_SESSION_ID]?.takeIf { it.isNotEmpty() }
            val stateName = prefs[KEY_STATE] ?: FocusSessionState.INACTIVE.name
            val state = try { FocusSessionState.valueOf(stateName) } catch (_: IllegalArgumentException) { FocusSessionState.INACTIVE }
            val plannedDuration = prefs[KEY_PLANNED_DURATION] ?: 0L
            val startTime = prefs[KEY_START_TIME] ?: 0L
            val endTime = prefs[KEY_END_TIME] ?: 0L
            val pauseStartedAt = prefs[KEY_PAUSE_STARTED_AT] ?: 0L
            val totalPaused = prefs[KEY_TOTAL_PAUSED] ?: 0L
            val monotonicStart = prefs[KEY_MONOTONIC_START] ?: 0L
            val savedVersion = prefs[KEY_VERSION] ?: 0L
            val streakPref = prefs[KEY_STREAK]
            val savedStreak = try { streakPref?.toIntOrNull() ?: 0 } catch (_: NumberFormatException) { 0 }
            val savedStrict = prefs[KEY_STRICT] ?: false

            if (sessionId != null && state != FocusSessionState.INACTIVE) {
                persistedVersion = savedVersion.toInt()

                // The persisted wall-clock end time is authoritative during recovery. Replacing
                // the monotonic baseline with "now" while retaining the original wall-clock
                // start makes the next drift check restore already-elapsed time.
                val restoredEndTime = endTime
                val restoredPauseStartedAt: Long? = if (pauseStartedAt > 0 && state == FocusSessionState.PAUSED) pauseStartedAt else null
                val restoredState = state
                val restoredMonotonicStart = if (monotonicStart > 0 && SystemClock.elapsedRealtime() >= monotonicStart) monotonicStart else 0L

                if (restoredState == FocusSessionState.ACTIVE) {
                    val remainingMs = restoredEndTime - System.currentTimeMillis()
                    if (remainingMs <= 0) {
                        val newStreak = savedStreak + 1
                        _status.update {
                            SessionStatus(
                                sessionId = sessionId,
                                state = FocusSessionState.COMPLETED,
                                plannedDurationMillis = plannedDuration,
                                startTimeMillis = startTime,
                                endTimeMillis = restoredEndTime,
                                pauseStartedAtMillis = null,
                                totalPausedDurationMillis = totalPaused,
                                monotonicStartMillis = restoredMonotonicStart,
                                version = savedVersion.toInt() + 1,
                                streakCount = newStreak,
                                strict = savedStrict
                            )
                        }
                        persistedVersion = _status.value.version
                        saveSessionState()
                        Log.i(TAG, "[FOCUS_INSTRUMENT] restore AUTO-COMPLETED sessionId=$sessionId newStreak=$newStreak")

                        val actualMs = _status.value.actualDurationMs()
                        scope.launch {
                            savePendingCompletion(sessionId, actualMs, plannedDuration, newStreak, startTime, restoredEndTime)
                            val cbs: List<suspend () -> Unit>
                            synchronized(onSessionCompleteCallbacks) { cbs = onSessionCompleteCallbacks.toList() }
                            for (cb in cbs) {
                                try { cb() } catch (e: Exception) { Log.e(TAG, "onSessionCompleteCallback error during restore", e) }
                            }
                        }
                        return
                    }
                }

                _status.update {
                    SessionStatus(
                        sessionId = sessionId,
                        state = restoredState,
                        plannedDurationMillis = plannedDuration,
                        startTimeMillis = startTime,
                        endTimeMillis = restoredEndTime,
                        pauseStartedAtMillis = if (restoredState == FocusSessionState.PAUSED) restoredPauseStartedAt else null,
                        totalPausedDurationMillis = totalPaused,
                        monotonicStartMillis = restoredMonotonicStart,
                        version = savedVersion.toInt(),
                        streakCount = savedStreak,
                        strict = savedStrict
                    )
                }

                Log.i(TAG, "[FOCUS_INSTRUMENT] restore OK sessionId=$sessionId state=$restoredState remaining=${_status.value.secondsRemaining}s version=$savedVersion streak=$savedStreak")

                if (restoredState == FocusSessionState.ACTIVE) {
                    Log.i(TAG, "[FOCUS_INSTRUMENT] restore starting ticker for restored ACTIVE session")
                    startTicker()
                    startForegroundService()
                    scheduleCompletionAlarm(restoredEndTime)
                } else if (restoredState == FocusSessionState.PAUSED) {
                    Log.i(TAG, "[FOCUS_INSTRUMENT] restore PAUSED — starting foreground service (no ticker)")
                    startForegroundService()
                }
            } else {
                Log.i(TAG, "[FOCUS_INSTRUMENT] restore nothing to restore (sessionId=$sessionId state=$stateName)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] Error restoring session state", e)
        }
    }

    private fun scheduleCompletionAlarm(endTimeMillis: Long) {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, FocusForegroundService::class.java).apply {
                action = FocusForegroundService.ACTION_COMPLETE
            }
            val pendingIntent = PendingIntent.getService(
                context, 
                FocusForegroundService.REQUEST_CODE_COMPLETE, 
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "[FOCUS_INSTRUMENT] Exact alarms not permitted, falling back to setAndAllowWhileIdle")
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, endTimeMillis, pendingIntent)
            }
            Log.i(TAG, "[FOCUS_INSTRUMENT] scheduleCompletionAlarm() set for $endTimeMillis")
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] Error setting completion alarm", e)
        }
    }

    private fun cancelCompletionAlarm() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val intent = Intent(context, FocusForegroundService::class.java).apply {
                action = FocusForegroundService.ACTION_COMPLETE
            }
            val pendingIntent = PendingIntent.getService(
                context, 
                FocusForegroundService.REQUEST_CODE_COMPLETE, 
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.i(TAG, "[FOCUS_INSTRUMENT] cancelCompletionAlarm() cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] Error cancelling completion alarm", e)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "studentos:focus_session_wakelock"
            ).apply {
                setReferenceCounted(false)
                acquire(2 * 60 * 60 * 1000L) // 2 hour timeout safety net
            }
            Log.i(TAG, "[FOCUS_INSTRUMENT] acquireWakeLock() — held=true")
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] acquireWakeLock() failed", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "[FOCUS_INSTRUMENT] releaseWakeLock() — held=false")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[FOCUS_INSTRUMENT] releaseWakeLock() error", e)
        }
        wakeLock = null
    }
}

package com.jainam.studentos.focusshield.feature.blocking

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CooldownManager {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val cooldownPeriods = ConcurrentHashMap<String, Long>()
    // Emergency passes earned from the warning screen: allow the app until this
    // elapsed-realtime timestamp without re-showing the warning.
    private val passUntil = ConcurrentHashMap<String, Long>()

    private val _cooldownActive = MutableStateFlow(false)
    val cooldownActive: StateFlow<Boolean> = _cooldownActive.asStateFlow()

    companion object {
        private const val TAG = "CooldownManager"
        // Reduced from 1500ms to 500ms: the old 1.5s debounce was swallowing
        // second accessibility events on aggressive OEM ROMs, causing the
        // "first attempt not blocked" glitch. 500ms is enough to prevent
        // duplicate warning dialogs while keeping the system responsive.
        private const val DEFAULT_COOLDOWN_MS = 500L
    }

    fun isInCooldown(packageName: String, minimumDebounceMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        val pass = passUntil[packageName]
        if (pass != null) {
            if (SystemClock.elapsedRealtime() < pass) return true
            passUntil.remove(packageName)
        }
        val lastBlocked = cooldownPeriods[packageName] ?: return false
        val elapsed = SystemClock.elapsedRealtime() - lastBlocked
        return elapsed < minimumDebounceMs
    }

    /** Grant a timed emergency pass for [packageName] (no re-block during the window). */
    fun grantPass(packageName: String, durationMs: Long) {
        passUntil[packageName] = SystemClock.elapsedRealtime() + durationMs
        Log.i(TAG, "Emergency pass granted for $packageName (${durationMs}ms)")
    }

    fun recordBlock(packageName: String) {
        cooldownPeriods[packageName] = SystemClock.elapsedRealtime()
        _cooldownActive.value = true
        Log.d(TAG, "Cooldown recorded for $packageName")
        scope.launch {
            delay(DEFAULT_COOLDOWN_MS)
            _cooldownActive.value = cooldownPeriods.values.any {
                SystemClock.elapsedRealtime() - it < DEFAULT_COOLDOWN_MS
            }
        }
    }

    fun clearCooldown(packageName: String) {
        cooldownPeriods.remove(packageName)
        _cooldownActive.value = cooldownPeriods.isNotEmpty()
    }

    fun reset() {
        cooldownPeriods.clear()
        passUntil.clear()
        _cooldownActive.value = false
    }

    fun cancel() {
        scope.cancel()
    }
}

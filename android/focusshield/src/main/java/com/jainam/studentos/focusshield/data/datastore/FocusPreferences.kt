package com.jainam.studentos.focusshield.data.datastore

import android.content.Context
import androidx.datastore.preferences.core.*
import com.jainam.studentos.focusshield.feature.blocking.BlockingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Persisted Smart Content Blocker state (survives process death / reboot). */
data class ContentFilterPrefs(
    val blockShorts: Boolean = false,
    val blockReels: Boolean = false,
    val blockTikTok: Boolean = false,
    val blockSnapchat: Boolean = false,
    val youtubeStudyMode: Boolean = false,
    val blockAdultUrls: Boolean = false,
    val blockedWebsites: Set<String> = emptySet(),
    /** Shorts/Reels allowed per focus session before the player is closed (0 = block on open). */
    val shortFormAllowance: Int = 1
)

class FocusPreferences(private val context: Context) {

    companion object {
        private val KEY_STREAK_COUNT = intPreferencesKey("streak_count")
        private val KEY_DND_PERMITTED = booleanPreferencesKey("dnd_permitted")
        private val KEY_ACCESSIBILITY_ACTIVE = booleanPreferencesKey("accessibility_active")
        private val KEY_BLOCKING_MODE = stringPreferencesKey("blocking_mode")
        private val KEY_COOLDOWN_MS = longPreferencesKey("cooldown_ms")
        private val KEY_PINNED_APPS = stringPreferencesKey("pinned_apps")
        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        private val KEY_ONBOARDING_STEP = intPreferencesKey("onboarding_step")
        private val KEY_PLANNER_INTEGRATION = booleanPreferencesKey("planner_integration")
        private val KEY_HABIT_TRIGGERS = stringPreferencesKey("habit_triggers")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_ACCENT_COLOR = stringPreferencesKey("accent_color")
        private val KEY_BLOCK_SHORTS = booleanPreferencesKey("block_shorts")
        private val KEY_BLOCK_REELS = booleanPreferencesKey("block_reels")
        private val KEY_BLOCK_TIKTOK = booleanPreferencesKey("block_tiktok")
        private val KEY_BLOCK_SNAPCHAT = booleanPreferencesKey("block_snapchat")
        private val KEY_YOUTUBE_STUDY_MODE = booleanPreferencesKey("youtube_study_mode")
        private val KEY_BLOCK_ADULT_URLS = booleanPreferencesKey("block_adult_urls")
        private val KEY_BLOCKED_WEBSITES = stringPreferencesKey("blocked_websites")
        private val KEY_SHORT_FORM_ALLOWANCE = intPreferencesKey("short_form_allowance")
    }

    val streakCountFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_STREAK_COUNT] ?: 0 }

    val dndPermittedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_DND_PERMITTED] ?: false }

    val blockingModeFlow: Flow<BlockingMode> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences ->
            val modeName = preferences[KEY_BLOCKING_MODE] ?: BlockingMode.STANDARD.name
            try { BlockingMode.valueOf(modeName) }
            catch (e: IllegalArgumentException) { BlockingMode.STANDARD }
        }

    val cooldownMsFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_COOLDOWN_MS] ?: 1500L }

    val pinnedAppsFlow: Flow<Set<String>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences ->
            val raw = preferences[KEY_PINNED_APPS] ?: ""
            if (raw.isBlank()) emptySet()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

    val onboardingCompletedFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_ONBOARDING_COMPLETED] ?: false }

    val onboardingStepFlow: Flow<Int> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_ONBOARDING_STEP] ?: 0 }

    val plannerIntegrationFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_PLANNER_INTEGRATION] ?: false }

    val habitTriggersFlow: Flow<Set<String>> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences ->
            val raw = preferences[KEY_HABIT_TRIGGERS] ?: ""
            if (raw.isBlank()) emptySet()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }

    val themeFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_THEME] ?: "system" }

    val accentColorFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences -> preferences[KEY_ACCENT_COLOR] ?: "purple" }

    val contentFiltersFlow: Flow<ContentFilterPrefs> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences())
            else throw exception
        }.map { preferences ->
            ContentFilterPrefs(
                blockShorts = preferences[KEY_BLOCK_SHORTS] ?: false,
                blockReels = preferences[KEY_BLOCK_REELS] ?: false,
                blockTikTok = preferences[KEY_BLOCK_TIKTOK] ?: false,
                blockSnapchat = preferences[KEY_BLOCK_SNAPCHAT] ?: false,
                youtubeStudyMode = preferences[KEY_YOUTUBE_STUDY_MODE] ?: false,
                blockAdultUrls = preferences[KEY_BLOCK_ADULT_URLS] ?: false,
                blockedWebsites = (preferences[KEY_BLOCKED_WEBSITES] ?: "")
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toSet(),
                shortFormAllowance = preferences[KEY_SHORT_FORM_ALLOWANCE] ?: 1
            )
        }

    suspend fun setContentFilters(filters: ContentFilterPrefs) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLOCK_SHORTS] = filters.blockShorts
            prefs[KEY_BLOCK_REELS] = filters.blockReels
            prefs[KEY_BLOCK_TIKTOK] = filters.blockTikTok
            prefs[KEY_BLOCK_SNAPCHAT] = filters.blockSnapchat
            prefs[KEY_YOUTUBE_STUDY_MODE] = filters.youtubeStudyMode
            prefs[KEY_BLOCK_ADULT_URLS] = filters.blockAdultUrls
            prefs[KEY_BLOCKED_WEBSITES] = filters.blockedWebsites.joinToString(",")
            prefs[KEY_SHORT_FORM_ALLOWANCE] = filters.shortFormAllowance
        }
    }

    suspend fun incrementStreak() {
        context.dataStore.edit { prefs ->
            prefs[KEY_STREAK_COUNT] = (prefs[KEY_STREAK_COUNT] ?: 0) + 1
        }
    }

    suspend fun resetStreak() {
        context.dataStore.edit { prefs ->
            prefs[KEY_STREAK_COUNT] = 0
        }
    }

    suspend fun setDndPermitted(permitted: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DND_PERMITTED] = permitted
        }
    }

    suspend fun setBlockingMode(mode: BlockingMode) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BLOCKING_MODE] = mode.name
        }
    }

    suspend fun setCooldownMs(ms: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COOLDOWN_MS] = ms
        }
    }

    suspend fun togglePinnedApp(packageName: String) {
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_PINNED_APPS] ?: ""
            val current = if (raw.isBlank()) mutableSetOf()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableSet()
            if (current.contains(packageName)) current.remove(packageName)
            else current.add(packageName)
            prefs[KEY_PINNED_APPS] = current.joinToString(",")
        }
    }

    suspend fun setPinnedApps(packages: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PINNED_APPS] = packages.joinToString(",")
        }
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_COMPLETED] = completed
        }
    }

    suspend fun setOnboardingStep(step: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ONBOARDING_STEP] = step
        }
    }

    suspend fun setPlannerIntegration(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PLANNER_INTEGRATION] = enabled
        }
    }

    suspend fun setHabitTriggers(triggers: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HABIT_TRIGGERS] = triggers.joinToString(",")
        }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme
        }
    }

    suspend fun setAccentColor(color: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCENT_COLOR] = color
        }
    }
}

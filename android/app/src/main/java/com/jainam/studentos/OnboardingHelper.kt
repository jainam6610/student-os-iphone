package com.jainam.studentos

import android.content.Context
import com.jainam.studentos.focusshield.data.datastore.FocusPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

object OnboardingHelper {
    @JvmStatic
    fun isOnboardingCompletedAsync(context: Context, callback: java.util.function.Consumer<Boolean>) {
        ioScope.launch {
            try {
                val value = FocusPreferences(context).onboardingCompletedFlow.first()
                callback.accept(value)
            } catch (_: Exception) {
                callback.accept(false)
            }
        }
    }

    @JvmStatic
    fun getSavedStepAsync(context: Context, callback: java.util.function.Consumer<Int>) {
        ioScope.launch {
            try {
                val value = FocusPreferences(context).onboardingStepFlow.first()
                callback.accept(value)
            } catch (_: Exception) {
                callback.accept(0)
            }
        }
    }

    @JvmStatic
    fun readThemeAsync(context: Context, callback: java.util.function.Consumer<String>) {
        ioScope.launch {
            try {
                val value = FocusPreferences(context).themeFlow.first()
                callback.accept(value)
            } catch (_: Exception) {
                callback.accept("system")
            }
        }
    }

    @JvmStatic
    fun readAccentColorAsync(context: Context, callback: java.util.function.Consumer<String>) {
        ioScope.launch {
            try {
                val value = FocusPreferences(context).accentColorFlow.first()
                callback.accept(value)
            } catch (_: Exception) {
                callback.accept("purple")
            }
        }
    }

    @JvmStatic
    fun readPlannerIntegrationAsync(context: Context, callback: java.util.function.Consumer<Boolean>) {
        ioScope.launch {
            try {
                val value = FocusPreferences(context).plannerIntegrationFlow.first()
                callback.accept(value)
            } catch (_: Exception) {
                callback.accept(false)
            }
        }
    }

    @JvmStatic
    fun setOnboardingCompleted(context: Context) {
        ioScope.launch {
            try {
                FocusPreferences(context).setOnboardingCompleted(true)
            } catch (_: Exception) { }
        }
    }

    @JvmStatic
    fun saveTheme(context: Context, theme: String) {
        ioScope.launch {
            try {
                FocusPreferences(context).setTheme(theme)
            } catch (_: Exception) { }
        }
    }

    @JvmStatic
    fun saveAccentColor(context: Context, color: String) {
        ioScope.launch {
            try {
                FocusPreferences(context).setAccentColor(color)
            } catch (_: Exception) { }
        }
    }
}

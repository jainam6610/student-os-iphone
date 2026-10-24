package com.jainam.studentos.focusshield.core.constants

object AppConfig {
    const val APP_PACKAGE_NAME = "com.jainam.studentos"

    const val DATABASE_NAME = "focus_shield_db"
    const val DATABASE_VERSION = 2

    const val TICKER_INTERVAL_MS = 1000L
    const val ACCESSIBILITY_DEBOUNCE_MS = 300L
    const val BYPASS_HOLD_DURATION_SECONDS = 5
    const val WARNING_COUNTDOWN_SECONDS = 5

    const val FOREGROUND_SERVICE_NOTIFICATION_ID = 1001
    const val SESSION_ALERTS_NOTIFICATION_ID = 1002
    const val NOTIFICATION_CHANNEL_ID_CORE = "focus_shield_core_channel"
    const val NOTIFICATION_CHANNEL_ID_ALERTS = "focus_shield_alerts_channel"

    const val DATASTORE_PREFERENCES_NAME = "focus_session_settings"

    const val TAG_CORE = "FocusShield_Core"
    const val TAG_DATABASE = "FocusShield_DB"
    const val TAG_ACCESSIBILITY = "FocusShield_Access"
    const val TAG_SERVICE = "FocusShield_Service"

    val SYSTEM_PACKAGES = setOf(
        "com.android.systemui",
        "com.android.settings",
        "android",
        "com.google.android.inputmethod.latin"
    )
}

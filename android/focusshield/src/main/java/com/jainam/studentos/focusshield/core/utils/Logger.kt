package com.jainam.studentos.focusshield.core.utils

import android.util.Log
import com.jainam.studentos.focusshield.BuildConfig

object Logger {
    private val isDebugLoggingEnabled: Boolean = BuildConfig.DEBUG

    fun d(tag: String, message: String) {
        if (isDebugLoggingEnabled) {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String) {
        if (isDebugLoggingEnabled) {
            Log.i(tag, message)
        }
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (isDebugLoggingEnabled) {
            Log.w(tag, message, throwable)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
    }
}

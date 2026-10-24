package com.jainam.studentos.focusshield.core.common

import com.jainam.studentos.focusshield.core.utils.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.CoroutineContext

object ErrorHandler {
    private const val TAG = "FocusShield_ErrorHandler"

    val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        logException(throwable, "Coroutine context crash caught by global handler.")
    }

    suspend fun <T> runCatchingSafe(
        fallbackValue: T,
        onException: ((Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): T {
        return try {
            block()
        } catch (e: Throwable) {
            logException(e, "Safe execution transaction failed.")
            onException?.invoke(e)
            fallbackValue
        }
    }

    fun logException(throwable: Throwable, contextMessage: String) {
        Logger.e(TAG, "[CRITICAL ERROR] Context: $contextMessage | Exception: ${throwable.localizedMessage}", throwable)
    }
}

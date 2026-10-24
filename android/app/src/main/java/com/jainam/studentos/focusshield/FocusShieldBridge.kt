package com.jainam.studentos.focusshield

import android.util.Log

object FocusShieldBridge {
    private const val TAG = "[FocusShieldBridge]"

    @Volatile
    private var manager: FocusShieldManager? = null

    @Volatile
    private var performGlobalActionImpl: ((Int) -> Boolean)? = null

    @JvmStatic
    fun getManager(): FocusShieldManager? = manager

    @JvmStatic
    fun getManagerOrNull(): FocusShieldManager? = manager

    @JvmStatic
    fun performGlobalAction(action: Int): Boolean {
        val result = performGlobalActionImpl?.invoke(action) ?: false
        Log.d(TAG, "performGlobalAction(action=$action) result=$result")
        return result
    }

    @JvmStatic
    fun initialize(fsm: FocusShieldManager, performGlobalAction: (Int) -> Boolean) {
        Log.i(TAG, "initialize() — manager=$fsm")
        manager = fsm
        performGlobalActionImpl = performGlobalAction
    }

    @JvmStatic
    fun clear() {
        Log.i(TAG, "clear() — nulling manager and global action")
        manager = null
        performGlobalActionImpl = null
    }
}
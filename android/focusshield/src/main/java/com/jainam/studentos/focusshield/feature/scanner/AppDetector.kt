package com.jainam.studentos.focusshield.feature.scanner

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache
import com.jainam.studentos.focusshield.core.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppDetector(private val context: Context) {

    companion object {
        private const val TAG = "FocusShield_Detector"
        private const val CACHE_SIZE = 100

        private val UNBLOCKABLE_PACKAGES = setOf(
            "com.jainam.studentos",
            "com.studentos",
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.nfc",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.keyguard",
            "com.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.android.captiveportallogin",
            "com.android.providers.downloads",
            "com.android.documentsui",
            "com.android.externalstorage",
            "com.android.companiondevicemanager",
            "com.android.carrierconfig",
            "com.android.inputmethod.latin",
            "com.android.bluetooth",
            "com.android.wifi",
            "com.android.cellbroadcast",
            "com.google.android.inputmethod.latin",
            "com.google.android.packageinstaller",
            "com.google.android.permissioncontroller",
            "com.google.android.setupwizard",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.coloros.safecenter",
            "com.nothing.launcher",
            "com.nothing.systemui",
            "com.qualcomm.qti.qtisystemservice"
        )
    }

    private val appNameCache = LruCache<String, String>(CACHE_SIZE)
    private val appIconCache = LruCache<String, Drawable>(CACHE_SIZE)

    suspend fun getAppName(packageName: String): String = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext "Unknown System Process"

        val cached = appNameCache.get(packageName)
        if (cached != null) return@withContext cached

        val pm = context.packageManager
        try {
            val appInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            val label = pm.getApplicationLabel(appInfo).toString()
            appNameCache.put(packageName, label)
            label
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    suspend fun getAppIcon(packageName: String): Drawable? = withContext(Dispatchers.IO) {
        if (packageName.isBlank()) return@withContext null

        val cached = appIconCache.get(packageName)
        if (cached != null) return@withContext cached

        try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            appIconCache.put(packageName, icon)
            icon
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    fun isUnblockable(packageName: String): Boolean {
        if (packageName.isBlank()) return true
        if (UNBLOCKABLE_PACKAGES.contains(packageName)) return true
        if (packageName.contains("inputmethod") || packageName.contains("keyboard")) return true
        return false
    }

    fun clearCache() {
        appNameCache.evictAll()
        appIconCache.evictAll()
        Logger.d(TAG, "Detector caches successfully evicted")
    }
}

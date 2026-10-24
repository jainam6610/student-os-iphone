package com.jainam.studentos.focusshield.feature.blocking

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import android.view.inputmethod.InputMethodInfo
import android.view.inputmethod.InputMethodManager

enum class AllowReason(val label: String) {
    SELF_PACKAGE("SELF_PACKAGE"),
    WARNING_ACTIVITY("WARNING_ACTIVITY"),
    FOREGROUND_SERVICE("FOREGROUND_SERVICE"),
    HOME_LAUNCHER("HOME_LAUNCHER"),
    ENABLED_KEYBOARD("ENABLED_KEYBOARD"),
    SYSTEM_UI("SYSTEM_UI"),
    PACKAGE_INSTALLER("PACKAGE_INSTALLER"),
    PERMISSION_CONTROLLER("PERMISSION_CONTROLLER"),
    ACCESSIBILITY_SETTINGS("ACCESSIBILITY_SETTINGS"),
    EMERGENCY_DIALER("EMERGENCY_DIALER"),
    CLOCK_ALARM("CLOCK_ALARM"),
    SYSTEM_CRITICAL("SYSTEM_CRITICAL"),
    USER_WHITELIST("USER_WHITELIST"),
    NO_SESSION("NO_SESSION"),
    COOLDOWN("COOLDOWN")
}

class EssentialSystemLayer(private val context: Context) {

    private val packageManager: PackageManager = context.packageManager
    private val inputMethodManager: InputMethodManager =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    private var cachedHomeLauncher: String? = null
    private var cachedHomeLauncherSet: Set<String> = emptySet()
    private var cachedEnabledKeyboards: Set<String> = emptySet()
    private var lastKeyboardCacheTime: Long = 0
    private val keyboardCacheDurationMs = 30_000L

    companion object {
        private const val TAG = "EssentialSystemLayer"
        private const val SELF_PACKAGE = "com.jainam.studentos"
        private const val WARNING_ACTIVITY_PACKAGE = "com.jainam.studentos"

        private val SYSTEM_CRITICAL_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.android.nfc",
            "com.android.bluetooth",
            "com.android.keyguard",
            "com.android.providers.downloads",
            "com.android.documentsui",
            "com.android.externalstorage",
            "com.android.wifi",
            "com.android.cellbroadcast",
            "com.android.inputmethod.latin",
            "com.android.companiondevicemanager",
            "com.jainam.studentos"
        )

        // Known OEM launchers. Every launcher must be treated as essential — blocking
        // the home screen bricks the device. Dynamic CATEGORY_HOME resolution covers
        // most of these, but this survives cases where resolveActivity/query returns
        // null (package-visibility timing, non-default launchers in the picker).
        private val KNOWN_LAUNCHER_PACKAGES = setOf(
            "com.sec.android.app.launcher",          // Samsung One UI Home
            "com.android.launcher",
            "com.android.launcher3",
            "com.google.android.apps.nexuslauncher", // Pixel Launcher
            "com.miui.home",
            "com.oneplus.launcher",
            "com.oppo.launcher",
            "com.coloros.launcher",
            "com.nothing.launcher",
            "com.huawei.android.launcher",
            "com.transsion.hilauncher",
            "com.transsion.XOSLauncher",
            "com.vivo.launcher",
            "com.bbk.launcher2",
            "com.microsoft.launcher",
            "com.teslacoilsw.launcher"
        )
    }

    fun isSystemCritical(packageName: String): Boolean {
        return checkPackage(packageName) != null
    }

    fun checkPackage(packageName: String): AllowReason? {
        if (packageName.isBlank()) return AllowReason.SYSTEM_CRITICAL
        if (packageName == SELF_PACKAGE) return AllowReason.SELF_PACKAGE
        if (isHomeLauncher(packageName)) return AllowReason.HOME_LAUNCHER
        if (isEnabledKeyboard(packageName)) return AllowReason.ENABLED_KEYBOARD
        if (isSystemUiOrSettings(packageName)) return AllowReason.SYSTEM_UI
        if (isPackageInstaller(packageName)) return AllowReason.PACKAGE_INSTALLER
        if (isPermissionController(packageName)) return AllowReason.PERMISSION_CONTROLLER
        if (isDialer(packageName)) return AllowReason.EMERGENCY_DIALER
        if (isClockOrAlarm(packageName)) return AllowReason.CLOCK_ALARM
        if (packageName in SYSTEM_CRITICAL_PACKAGES) return AllowReason.SYSTEM_CRITICAL
        if (packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.android.settings") ||
            packageName.startsWith("com.android.phone") ||
            packageName.startsWith("com.android.nfc") ||
            packageName.startsWith("com.android.bluetooth") ||
            packageName.startsWith("com.google.android.apps.work")) {
            return AllowReason.SYSTEM_UI
        }
        return null
    }

    fun getAllowReason(packageName: String): String {
        val reason = checkPackage(packageName)
        return reason?.label ?: "BLOCKED"
    }

    private fun isSystemApp(packageName: String): Boolean = try {
        val ai = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }
        ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    } catch (_: Exception) { false }

    fun clearCaches() {
        cachedHomeLauncher = null
        cachedHomeLauncherSet = emptySet()
        cachedEnabledKeyboards = emptySet()
        lastKeyboardCacheTime = 0
    }

    private fun isHomeLauncher(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        // Fast static fallback first — survives resolution failures on any OEM.
        if (packageName in KNOWN_LAUNCHER_PACKAGES) return true
        if (cachedHomeLauncher == packageName) return true
        if (resolveHomeLauncherPackage() == packageName) {
            cachedHomeLauncher = packageName
            return true
        }
        // Every app that declares a HOME activity is a launcher (covers the
        // launcher-picker case where there is no single default).
        return packageName in getAllHomePackages()
    }

    private fun getAllHomePackages(): Set<String> {
        if (cachedHomeLauncherSet.isNotEmpty()) return cachedHomeLauncherSet
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        cachedHomeLauncherSet = try {
            val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            infos.mapNotNull { it.activityInfo?.packageName }.toSet()
        } catch (_: Exception) { emptySet() }
        return cachedHomeLauncherSet
    }

    private fun resolveHomeLauncherPackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        return resolveInfo?.activityInfo?.packageName
    }

    private fun isEnabledKeyboard(packageName: String): Boolean {
        refreshKeyboardCacheIfNeeded()
        return packageName in cachedEnabledKeyboards || packageName.contains("inputmethod") || packageName.contains("keyboard")
    }

    private fun refreshKeyboardCacheIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastKeyboardCacheTime < keyboardCacheDurationMs && cachedEnabledKeyboards.isNotEmpty()) return
        lastKeyboardCacheTime = now
        try {
            val enabledMethods: List<InputMethodInfo> = inputMethodManager.enabledInputMethodList
            cachedEnabledKeyboards = enabledMethods.map { it.packageName }.toSet()
            Log.d(TAG, "Cached ${cachedEnabledKeyboards.size} enabled keyboards: $cachedEnabledKeyboards")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query enabled input methods", e)
        }
    }

    private fun isSystemUiOrSettings(packageName: String): Boolean {
        if (packageName in SYSTEM_CRITICAL_PACKAGES) return true
        if (packageName == "com.android.settings") return true
        if (packageName == Settings.ACTION_ACCESSIBILITY_SETTINGS) return false
        val settingsPkg = resolveSettingsPackage()
        if (settingsPkg == packageName) return true
        return false
    }

    private fun resolveSettingsPackage(): String? {
        val intent = Intent(Settings.ACTION_SETTINGS)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        return resolveInfo?.activityInfo?.packageName
    }

    private fun isPackageInstaller(packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, 0)
        }
        if (resolveInfo?.activityInfo?.packageName == packageName) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val rolePkg = packageManager.getInstallerPackageName(packageName)
            if (rolePkg == packageName) return true
        }
        return false
    }

    private fun isPermissionController(packageName: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
            if (resolveInfo?.activityInfo?.packageName == packageName) return true
        }
        val permissionControllerPkg = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val pm = packageManager
            try {
                val info = pm.getApplicationInfo(
                    "com.android.permissioncontroller",
                    PackageManager.ApplicationInfoFlags.of(0)
                )
                info.packageName
            } catch (_: Exception) { null }
        } else {
            @Suppress("DEPRECATION")
            try {
                val info = packageManager.getApplicationInfo("com.android.permissioncontroller", 0)
                info.packageName
            } catch (_: Exception) { null }
        }
        if (permissionControllerPkg == packageName) return true
        return false
    }

    private fun isDialer(packageName: String): Boolean {
        val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
        if (telecomManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val defaultDialer = telecomManager.defaultDialerPackage
                if (defaultDialer == packageName) return true
            } catch (_: Exception) { }
        }
        val dialIntent = Intent(Intent.ACTION_DIAL)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(dialIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(dialIntent, 0)
        }
        if (resolveInfo?.activityInfo?.packageName == packageName) return true
        return false
    }

    private fun isClockOrAlarm(packageName: String): Boolean {
        val alarmIntent = Intent(AlarmClock.ACTION_SET_ALARM)
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(alarmIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(alarmIntent, 0)
        }
        if (resolveInfo?.activityInfo?.packageName == packageName) return true

        if (packageName.contains("clock") || packageName.contains("alarm") || packageName.contains("deskclock")) {
            return true
        }

        return false
    }
}

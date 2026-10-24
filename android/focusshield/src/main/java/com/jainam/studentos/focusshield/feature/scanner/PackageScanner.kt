package com.jainam.studentos.focusshield.feature.scanner

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import com.jainam.studentos.focusshield.core.utils.Logger
import com.jainam.studentos.focusshield.data.CategoryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PackageScanner(private val context: Context) {

    companion object {
        private const val TAG = "FocusShield_Scanner"
        private const val MAX_CACHE_ENTRIES = 200

        private val KEYWORDS_STUDY = setOf("edu", "course", "learn", "quiz", "notion", "duolingo", "udemy")
        private val KEYWORDS_COMMUNICATION = setOf("mail", "chat", "message", "whatsapp", "slack", "discord", "skype", "teams")
        private val KEYWORDS_PRODUCTIVITY = setOf("office", "drive", "sheet", "doc", "notes", "calendar", "task", "todo")
        private val KEYWORDS_ENTERTAINMENT = setOf("youtube", "netflix", "spotify", "twitch", "prime", "music", "video")
        private val KEYWORDS_GAMES = setOf("game", "arcade", "puzzle", "action", "rpg", "chess", "sudoku")
        private val KEYWORDS_SOCIAL_MEDIA = setOf("instagram", "twitter", "facebook", "tiktok", "snapchat", "reddit", "linkedin")
        private val KEYWORDS_FINANCE = setOf("wallet", "bank", "pay", "finance", "crypto", "cash")
        private val KEYWORDS_SHOPPING = setOf("shop", "store", "amazon", "ebay", "cart")
    }

    private val iconCache = object : LruCache<String, Drawable>(MAX_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Drawable): Int {
            return 1
        }
    }

    private var packageCache: List<ScannedApp>? = null

    suspend fun scanInstalledApps(forceRefresh: Boolean = false): List<ScannedApp> = withContext(Dispatchers.IO) {
        if (!forceRefresh && packageCache != null) {
            Logger.d(TAG, "Serving installed packages from memory cache")
            return@withContext packageCache!!
        }

        Logger.i(TAG, "Initializing full-device package scan")
        val pm = context.packageManager
        val scannedList = mutableListOf<ScannedApp>()

        try {
            val seenPackages = mutableSetOf<String>()

            // Primary: query launcher activities
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                @Suppress("UNCHECKED_CAST")
                pm.queryIntentActivities(mainIntent, 0) as List<android.content.pm.ResolveInfo>
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, 0)
            }

            for (resolveInfo in resolveInfos) {
                val packageName = resolveInfo.activityInfo?.packageName
                if (packageName.isNullOrBlank() || seenPackages.contains(packageName)) continue
                seenPackages.add(packageName)

                val appInfo = resolveInfo.activityInfo.applicationInfo
                val appName = resolveInfo.loadLabel(pm).toString()
                val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val category = classifyApp(packageName, appName, isSystem)

                scannedList.add(ScannedApp(
                    packageName = packageName,
                    appName = appName,
                    isSystemApp = isSystem,
                    categoryType = category,
                    iconName = getLucideFallbackIconName(category)
                ))
            }

            scannedList.sortBy { it.appName.lowercase() }
            packageCache = scannedList

        } catch (e: Exception) {
            Logger.e(TAG, "Error compiling installed packages", e)
        }

        return@withContext scannedList
    }

    fun getOemHomePackage(): String? {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)
        }
        return resolveInfo?.activityInfo?.packageName
    }

    private val essentialLayer by lazy { com.jainam.studentos.focusshield.feature.blocking.EssentialSystemLayer(context) }

    /**
     * Scanning must NOT decide what gets blocked. Everything the user has not
     * explicitly picked is UNCATEGORIZED, which the engine allows in blocklist
     * mode (default). Previously every installed app was persisted as BLOCKED,
     * which is why unrelated, perfectly fine apps were blocked the moment a
     * session started.
     */
    fun classifyApp(packageName: String, appName: String, isSystem: Boolean): CategoryType {
        if (essentialLayer.checkPackage(packageName) != null) {
            return CategoryType.ESSENTIAL
        }
        return CategoryType.UNCATEGORIZED
    }

    fun getAppIcon(packageName: String): Drawable? {
        val cached = iconCache.get(packageName)
        if (cached != null) return cached

        return try {
            val icon = context.packageManager.getApplicationIcon(packageName)
            iconCache.put(packageName, icon)
            icon
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun getLucideFallbackIconName(category: CategoryType): String {
        return when (category) {
            CategoryType.ESSENTIAL -> "Settings"
            CategoryType.STUDY -> "BookOpen"
            CategoryType.USER_ALLOWED -> "MessageSquare"
            CategoryType.BLOCKED -> "Youtube"
            CategoryType.UNCATEGORIZED -> "Circle"
            CategoryType.SOCIAL_MEDIA -> "Youtube"
            CategoryType.ENTERTAINMENT -> "Youtube"
            CategoryType.GAMES -> "Youtube"
            CategoryType.SHOPPING -> "Youtube"
            CategoryType.ALWAYS_BLOCKED -> "Ban"
            CategoryType.BREAK_BLOCKED -> "ShieldOff"
        }
    }
}

data class ScannedApp(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    var categoryType: CategoryType,
    val iconName: String,
    val installTime: Long = 0L,
    val usageMinutesToday: Int = 0
)

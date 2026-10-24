package com.jainam.studentos.focusshield.feature.blocking

import android.content.Context
import android.util.Log
import com.jainam.studentos.focusshield.BuildConfig
import com.jainam.studentos.focusshield.data.AppCategoryDao
import com.jainam.studentos.focusshield.data.CategoryType
import com.jainam.studentos.focusshield.data.DistractionDao
import com.jainam.studentos.focusshield.feature.scanner.AppDetector
import com.jainam.studentos.focusshield.manager.FocusSessionManager
import com.jainam.studentos.focusshield.manager.FocusSessionState
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class BlockingDecision {
    object NoSession : BlockingDecision()
    object Allowed : BlockingDecision()
    object CooldownActive : BlockingDecision()
    object WarningAlreadyShowing : BlockingDecision()
    object Block : BlockingDecision()
}

class BlockingEngine(
    private val context: Context,
    private val appCategoryDao: AppCategoryDao,
    private val distractionDao: DistractionDao,
    private val appDetector: AppDetector,
    private val sessionManager: FocusSessionManager,
    private val essentialSystemLayer: EssentialSystemLayer
) {
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Uncaught coroutine exception", throwable)
    }
    private val engineJob = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + engineJob + exceptionHandler)
    private val cooldownManager = CooldownManager()
    private val distractionTracker = DistractionTracker(distractionDao)

    /** Per-event logs are debug-only; release builds stay quiet. */
    private val verbose = BuildConfig.DEBUG

    private val _blockingMode = MutableStateFlow(BlockingMode.STANDARD)
    val blockingMode: StateFlow<BlockingMode> = _blockingMode.asStateFlow()

    private val _lastDecision = MutableStateFlow<BlockingDecision>(BlockingDecision.NoSession)
    val lastDecision: StateFlow<BlockingDecision> = _lastDecision.asStateFlow()

    private val _distractionCountForSession = MutableStateFlow(0)
    val distractionCountForSession: StateFlow<Int> = _distractionCountForSession.asStateFlow()

    // Break-time blocking: when session is PAUSED, these packages are still blocked.
    @Volatile
    private var breakBlockingEnabled: Boolean = false
    private val breakBlockedPackages = ConcurrentHashMap.newKeySet<String>()

    // ── Content filters — user controlled ────────────────────────────────────
    // Every filter below is OFF until the user turns it on in Deep Lock settings.
    // Blocking content without consent is both a policy and a trust problem, and
    // these switches used to be UI-only (flipping them off changed nothing).
    @Volatile
    private var blockShortsEnabled: Boolean = false
    @Volatile
    private var blockReelsEnabled: Boolean = false
    @Volatile
    private var blockTikTokEnabled: Boolean = false
    @Volatile
    private var blockSnapchatEnabled: Boolean = false
    @Volatile
    private var blockAdultUrlsEnabled: Boolean = false
    /** YouTube Study Mode: gate the Home/browse feed; search + videos stay usable. */
    @Volatile
    private var youtubeStudyModeEnabled: Boolean = false

    fun setContentFilters(
        blockShorts: Boolean,
        blockReels: Boolean,
        blockAdultUrls: Boolean,
        blockTikTok: Boolean = false,
        blockSnapchat: Boolean = false,
        youtubeStudyMode: Boolean = false
    ) {
        blockShortsEnabled = blockShorts
        blockReelsEnabled = blockReels
        blockAdultUrlsEnabled = blockAdultUrls
        blockTikTokEnabled = blockTikTok
        blockSnapchatEnabled = blockSnapchat
        youtubeStudyModeEnabled = youtubeStudyMode
        Log.i(
            TAG,
            "setContentFilters() shorts=$blockShorts reels=$blockReels tiktok=$blockTikTok " +
                "snapchat=$blockSnapchat studyMode=$youtubeStudyMode adultUrls=$blockAdultUrls"
        )
    }

    fun setYoutubeStudyMode(enabled: Boolean) {
        youtubeStudyModeEnabled = enabled
        Log.i(TAG, "setYoutubeStudyMode() → $enabled")
    }

    val isYoutubeStudyModeEnabled: Boolean get() = youtubeStudyModeEnabled

    /**
     * The "Are you on track?" check-in runs outside focus sessions, so it is
     * gated by the Motivation Engine toggle rather than by a session.
     */
    @Volatile
    private var checkInEnabled: Boolean = false

    fun setCheckInEnabled(enabled: Boolean) {
        checkInEnabled = enabled
        Log.i(TAG, "setCheckInEnabled() → $enabled")
    }

    val isCheckInEnabled: Boolean get() = checkInEnabled

    /**
     * How many shorts/reels the user is allowed per focus session before the
     * player is closed for them (0 = block on open).
     */
    @Volatile
    private var shortFormAllowance: Int = ShortFormGuard.DEFAULT_ALLOWANCE

    fun setShortFormAllowance(value: Int) {
        shortFormAllowance = value.coerceIn(0, ShortFormGuard.MAX_ALLOWANCE)
        Log.i(TAG, "setShortFormAllowance() → $shortFormAllowance")
    }

    val shortFormAllowanceValue: Int get() = shortFormAllowance

    /**
     * Is the short-form player of [packageName] enforced right now? The toggle
     * for the host app decides, and the app must have a known short-form surface.
     */
    fun isShortFormEnabledFor(packageName: String): Boolean {
        // Whole-app short-form (TikTok) has no single "surface" — the filter
        // covers the entire package, so it is checked before the surface map.
        if (packageName in ShortFormRules.WHOLE_APP_SHORT_FORM_PACKAGES) {
            return blockTikTokEnabled
        }
        if (ShortFormRules.surfaceFor(packageName) == null) return false
        return when (packageName) {
            "com.google.android.youtube" -> blockShortsEnabled
            "com.instagram.android" -> blockReelsEnabled
            "com.snapchat.android" -> blockSnapchatEnabled
            else -> false
        }
    }

    /**
     * Packages the "Are you on track?" check-in watches: the short-form apps plus
     * every browser (where the reel/short rabbit holes live too).
     */
    fun isDistractingShortFormPackage(packageName: String): Boolean =
        packageName == "com.google.android.youtube" ||
            packageName == "com.instagram.android" ||
            packageName == "com.snapchat.android" ||
            packageName in ShortFormRules.WHOLE_APP_SHORT_FORM_PACKAGES ||
            isBrowserPackage(packageName)

    /**
     * True when the user has any content filter on that is enforced outside a
     * focus session. The accessibility observer uses this to stay awake and
     * block *surfaces* (Shorts/Reels/TikTok/Snapchat/websites) even when no
     * session is running — the "always-on but lazy" model.
     */
    val hasAlwaysOnContent: Boolean
        get() = blockShortsEnabled ||
            blockReelsEnabled ||
            blockTikTokEnabled ||
            blockSnapchatEnabled ||
            youtubeStudyModeEnabled ||
            isUrlBlockingActive

    /** False when no content filter is on — the check-in has nothing to guard. */
    val isAnyContentFilterOn: Boolean
        get() = hasAlwaysOnContent

    // Activity-level block patterns (legacy, className based)
    // These are kept only as a last-resort net for OEM builds that still expose a
    // dedicated short-form activity. They do NOT fire on stock YouTube/Instagram:
    // Shorts and Reels are fragments inside Shell$HomeActivity / MainTabActivity,
    // so both are enforced by ShortFormRules + the accessibility view tree in
    // FocusShieldManager instead.
    private val SHORTS_ACTIVITY_PATTERNS = listOf(
        "com.google.android.youtube.shortsactivity",
        "com.google.android.youtube.shorts."
    )

    private val REELS_ACTIVITY_PATTERNS = listOf(
        "com.instagram.reels.",
        "com.instagram.feed.reels.",
        "com.snap.android.spotlight"
    )

    // Package-level short-form video apps. Only enforced while the matching
    // filter is enabled — nothing in this engine blocks an app 24/7 behind the
    // user's back (ALWAYS_BLOCKED is a user-maintained list in the UI).
    private val SHORTS_PACKAGES = ShortFormRules.WHOLE_APP_SHORT_FORM_PACKAGES

    // Browser package names — used to check URL patterns in accessibility events
    val BROWSER_PACKAGES = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "org.mozilla.firefox_beta",
        "com.microsoft.emmx",            // Edge
        "com.opera.browser",
        "com.opera.mini.native",
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.vivaldi.browser",
        "com.kiwibrowser.browser",
        "com.sec.android.app.sbrowser",  // Samsung Internet
        "com.android.browser",
        "com.huawei.browser",
        "com.mi.globalbrowser",
        "com.UCMobile.intl",
        "com.yandex.browser",
        "org.chromium.chrome"
    )

    /**
     * The device's default browser. Many phones ship browsers that are not in
     * the list above (custom OEM or lightweight browsers) and without this a
     * website filter silently does nothing on those devices.
     */
    private val defaultBrowserPackage: String? by lazy {
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://www.example.com")
            ).addCategory(android.content.Intent.CATEGORY_BROWSABLE)
            context.packageManager
                .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.also { Log.i(TAG, "default browser package: $it") }
        } catch (e: Exception) {
            Log.w(TAG, "could not resolve default browser", e)
            null
        }
    }

    fun isBrowserPackage(packageName: String): Boolean =
        packageName in BROWSER_PACKAGES || packageName == defaultBrowserPackage

    // Adult URL domain patterns for browser blocking
    private val ADULT_URL_PATTERNS = UrlMatcher.ADULT_PATTERNS

    // ── User-maintained website block list ───────────────────────────────────
    // Bare hostnames ("instagram.com"). Applied to every browser package while
    // the shield is armed — no separate session requirement, this is the user's
    // explicit opt-in list.
    @Volatile
    private var blockedWebsites: Set<String> = emptySet()

    fun setBlockedWebsites(domains: Set<String>) {
        val cleaned = UrlMatcher.normalizeDomainList(domains)
        blockedWebsites = cleaned
        Log.i(TAG, "setBlockedWebsites() → ${cleaned.size} domain(s): ${cleaned.joinToString(",")}")
    }

    val blockedWebsiteCount: Int
        get() = blockedWebsites.size

    val shortsFilterEnabled: Boolean get() = blockShortsEnabled
    val reelsFilterEnabled: Boolean get() = blockReelsEnabled
    val tikTokFilterEnabled: Boolean get() = blockTikTokEnabled
    val snapchatFilterEnabled: Boolean get() = blockSnapchatEnabled
    val studyModeFilterEnabled: Boolean get() = youtubeStudyModeEnabled
    val adultFilterEnabled: Boolean get() = blockAdultUrlsEnabled

    fun blockedWebsiteSnapshot(): List<String> = blockedWebsites.sorted()

    /** True when at least one browser URL rule is enabled. */
    val isUrlBlockingActive: Boolean
        get() = blockAdultUrlsEnabled || blockedWebsites.isNotEmpty()

    /**
     * Check any browser text (address bar, page title, URL) against the user's
     * block list and the NSFW patterns.
     */
    fun isUrlBlocked(text: String?): Boolean =
        UrlMatcher.isBlocked(text, blockedWebsites, blockAdultUrlsEnabled)

    companion object {
        private const val TAG = "[BlockingEngine]"

        /** How long to wait for the block screen to prove it is on screen. */
        private const val WARNING_VISIBILITY_TIMEOUT_MS = 2_000L

        val isWarningScreenActive = AtomicBoolean(false)
    }

    init {
        FocusSessionManager.addOnSessionCompleteCallback {
            resetSessionCounters()
        }
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Set by [WarningActivity.onCreate]. `startActivity` from the background can
     * be silently dropped by the OS (no exception), which used to leave
     * [isWarningScreenActive] latched true forever — every later block was then
     * swallowed as WARNING_ALREADY_SHOWING. The watchdog below releases the latch
     * when the screen never appears.
     */
    @Volatile
    private var warningVisible = false
    private var warningWatchdog: Runnable? = null

    /** Called by the block screen once it is on screen. */
    fun onWarningVisible() {
        warningVisible = true
        Log.i(TAG, "onWarningVisible() — block screen confirmed")
    }

    private fun disarmWarningWatchdog() {
        warningWatchdog?.let { mainHandler.removeCallbacks(it) }
        warningWatchdog = null
        warningVisible = false
    }

    fun setBlockingMode(mode: BlockingMode) {
        _blockingMode.value = mode
        Log.d(TAG, "Blocking mode set to $mode")
    }

    fun setBreakBlockingEnabled(enabled: Boolean) {
        breakBlockingEnabled = enabled
        Log.i(TAG, "breakBlockingEnabled=$enabled")
    }

    val isBreakBlockingEnabled: Boolean
        get() = breakBlockingEnabled

    /**
     * True when the user has anything on the 24/7 / break list. Used to keep the
     * accessibility observer awake outside sessions, where those apps must still
     * be blocked (the list is advertised as working 24/7).
     */
    val hasBreakBlockedPackages: Boolean
        get() = breakBlockedPackages.isNotEmpty()
    /** True when an idle 24/7 app list needs foreground enforcement. */
    val hasAlwaysBlockedPackages: Boolean
        get() = categoryCache.values.any { it == CategoryType.ALWAYS_BLOCKED }

    fun updateBreakBlockedPackages(packages: Set<String>) {
        breakBlockedPackages.clear()
        breakBlockedPackages.addAll(packages)
        Log.i(TAG, "breakBlockedPackages updated: ${packages.size} packages")
    }

    // ── Category cache ────────────────────────────────────────────────────────
    // handleForegroundApp() runs on every foreground change and poller tick, and
    // used to read Room each time. The category table is small; keep it in memory
    // and refresh only when a block list actually changes.
    private val categoryCache = ConcurrentHashMap<String, CategoryType>()

    @Volatile
    private var categoryCacheLoaded = false

    /** Reload the in-memory category table. Call after any block-list write. */
    suspend fun refreshCategoryCache() {
        try {
            applyCategorySnapshot(appCategoryDao.getAllCategories())
        } catch (e: Exception) {
            Log.e(TAG, "refreshCategoryCache() failed — falling back to Room", e)
            categoryCacheLoaded = false
        }
    }

    /**
     * Swap the cache for a Room snapshot. The manager collects the category flow
     * so block-list writes from ANY surface (JS bridge, native picker, scanner)
     * land here without a per-event query.
     */
    fun applyCategorySnapshot(rows: List<com.jainam.studentos.focusshield.data.AppCategory>) {
        val fresh = HashMap<String, CategoryType>(rows.size)
        for (row in rows) fresh[row.packageName] = row.categoryType
        categoryCache.clear()
        categoryCache.putAll(fresh)
        categoryCacheLoaded = true
        Log.i(TAG, "applyCategorySnapshot() — ${fresh.size} entries cached")
    }

    /** Cache-first category lookup. Falls back to Room until the cache has loaded. */
    private suspend fun categoryTypeOf(packageName: String): CategoryType? {
        if (categoryCacheLoaded) return categoryCache[packageName]
        return try {
            appCategoryDao.getCategoryForPackage(packageName)?.categoryType?.also {
                categoryCache[packageName] = it
            }
        } catch (e: Exception) {
            Log.e(TAG, "DB error fetching category for $packageName", e)
            null
        }
    }

    // Fail-safe: false when the effective allowlist is empty or could not be
    // loaded. In that state an unknown package must NOT be blocked (blocking
    // everything is worse than blocking nothing when the allowlist is broken).
    @Volatile
    private var allowlistReady: Boolean = true

    fun setAllowlistReady(ready: Boolean) {
        allowlistReady = ready
        Log.i(TAG, "allowlistReady=$ready")
    }

    /**
     * Check if an activity class name from an accessibility event should be blocked.
     * Used to block YouTube Shorts, Instagram Reels, etc. even when the parent
     * app is whitelisted.
     */
    fun isActivityBlocked(packageName: String, className: String?): Boolean {
        if (className == null) return false
        val lowerClass = className.lowercase()

        // ── TikTok (whole-app short-form) ──
        if (blockTikTokEnabled && packageName in SHORTS_PACKAGES) {
            Log.i(TAG, "isActivityBlocked: TikTok package=$packageName (whole-app)")
            return true
        }

        // ── Snapchat Spotlight / Stories ──
        // Snapchat never exposes a dedicated activity for these feeds (they are
        // fragments of MainActivity), so activity-level matching can only catch
        // OEM builds that do. The reliable path is the view-tree text hints in
        // FocusShieldManager.handleShortFormVisibility().
        if (blockSnapchatEnabled && packageName == "com.snapchat.android") {
            if (lowerClass.contains("spotlight") ||
                lowerClass.contains("discover") ||
                lowerClass.contains("stories") ||
                lowerClass.contains("story")
            ) {
                Log.i(TAG, "isActivityBlocked: Snapchat spotlight/stories detected className=$className")
                return true
            }
        }

        // ── Shorts filter ──
        if (blockShortsEnabled) {
            if (packageName in SHORTS_PACKAGES) return true
            for (pattern in SHORTS_ACTIVITY_PATTERNS) {
                if (lowerClass.contains(pattern)) {
                    Log.i(TAG, "isActivityBlocked: Shorts className=$className matches=$pattern")
                    return true
                }
            }
            if (packageName == "com.google.android.youtube" && lowerClass.contains("short")) {
                Log.i(TAG, "isActivityBlocked: YouTube Shorts detected className=$className")
                return true
            }
        }

        // ── Reels filter ──
        if (blockReelsEnabled) {
            for (pattern in REELS_ACTIVITY_PATTERNS) {
                if (lowerClass.contains(pattern)) {
                    Log.i(TAG, "isActivityBlocked: Reels className=$className matches=$pattern")
                    return true
                }
            }
            if (packageName == "com.instagram.android" &&
                (lowerClass.contains("reel") || lowerClass.contains("clips"))
            ) {
                Log.i(TAG, "isActivityBlocked: Instagram Reels detected className=$className")
                return true
            }
            if (packageName == "com.snapchat.android" &&
                (lowerClass.contains("spotlight") || lowerClass.contains("discover"))
            ) {
                Log.i(TAG, "isActivityBlocked: Snapchat Spotlight detected className=$className")
                return true
            }
        }

        return false
    }

    /**
     * Check if accessibility event text (e.g. URL bar) matches adult content
     * patterns or the user's own blocked-website list.
     */
    fun isAdultUrlDetected(text: String?): Boolean = isUrlBlocked(text)

    suspend fun handleForegroundApp(packageName: String): Pair<BlockingDecision, String> {
        val currentSession = sessionManager.status.value

        if (verbose) Log.d(TAG, "handleForegroundApp(packageName=$packageName state=${currentSession.state})")

        // Short-form video packages are handled by the opt-in Shorts filter in
        // isActivityBlocked(); there is no hidden hardcoded blocklist here any
        // more. ALWAYS_BLOCKED is the user's own list (Deep Lock → 24/7 blocked).

        // ── Break-time blocking (session PAUSED) ──
        if (currentSession.state == FocusSessionState.PAUSED && breakBlockingEnabled) {
            val isBreakBlocked = try {
                val cat = categoryTypeOf(packageName)
                cat == CategoryType.ALWAYS_BLOCKED ||
                cat == CategoryType.BREAK_BLOCKED ||
                packageName in breakBlockedPackages
            } catch (e: Exception) {
                Log.e(TAG, "DB error checking break-blocked for $packageName — fail-safe ALLOW", e)
                false
            }

            if (isBreakBlocked) {
                if (essentialSystemLayer.checkPackage(packageName) != null) {
                    return Pair(BlockingDecision.Allowed, "ESSENTIAL_SYSTEM_DURING_BREAK")
                }
                if (cooldownManager.isInCooldown(packageName)) {
                    return Pair(BlockingDecision.CooldownActive, "COOLDOWN_ACTIVE_BREAK")
                }
                if (isWarningScreenActive.get()) {
                    return Pair(BlockingDecision.WarningAlreadyShowing, "WARNING_ALREADY_SHOWING_BREAK")
                }
                cooldownManager.recordBlock(packageName)
                val appName = try { appDetector.getAppName(packageName) } catch (_: Exception) { packageName }
                launchWarningActivity(packageName, appName)
                _lastDecision.value = BlockingDecision.Block
                return Pair(BlockingDecision.Block, "BREAK_BLOCKED")
            }
            // Not break-blocked → allow during break
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "BREAK_ALLOWED")
        }

        // ── Normal session check ──
        if (currentSession.state != FocusSessionState.ACTIVE) {
            // Check if it's in ALWAYS_BLOCKED DB category even outside sessions
            try {
                val cat = categoryTypeOf(packageName)
                if (cat == CategoryType.ALWAYS_BLOCKED) {
                    if (essentialSystemLayer.checkPackage(packageName) != null) {
                        return Pair(BlockingDecision.Allowed, "ESSENTIAL_SYSTEM")
                    }
                    if (cooldownManager.isInCooldown(packageName)) {
                        return Pair(BlockingDecision.CooldownActive, "COOLDOWN_ACTIVE")
                    }
                    if (isWarningScreenActive.get()) {
                        return Pair(BlockingDecision.WarningAlreadyShowing, "WARNING_ALREADY_SHOWING")
                    }
                    cooldownManager.recordBlock(packageName)
                    val appName = try { appDetector.getAppName(packageName) } catch (_: Exception) { packageName }
                    launchWarningActivity(packageName, appName)
                    _lastDecision.value = BlockingDecision.Block
                    return Pair(BlockingDecision.Block, "ALWAYS_BLOCKED_NO_SESSION")
                }
            } catch (_: Exception) {}
            if (verbose) Log.d(TAG, "handleForegroundApp() → NO_SESSION (state=${currentSession.state})")
            _lastDecision.value = BlockingDecision.NoSession
            return Pair(BlockingDecision.NoSession, "NO_SESSION")
        }

        // The allowlist only matters in whitelist (STRICT) mode. In blocklist
        // (STANDARD/BREAK) mode an empty allowlist is normal and must never
        // disable the user's explicit blocks.
        if (BlockingRules.requiresAllowlist(_blockingMode.value) && !allowlistReady) {
            Log.w(TAG, "handleForegroundApp() → ALLOW (STRICT mode, allowlist not ready — fail-safe) pkg=$packageName")
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "ALLOWLIST_NOT_READY")
        }

        // System-critical/launcher/keyboard/etc. are never blocked, regardless of
        // how they landed in the DB.
        if (essentialSystemLayer.checkPackage(packageName) != null) {
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "ESSENTIAL_SYSTEM")
        }

        val categoryType = try {
            categoryTypeOf(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "DB error fetching category for $packageName — fail-safe ALLOW", e)
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "DB_ERROR_FAILSAFE")
        }

        if (verbose) {
            Log.d(TAG, "handleForegroundApp() category=${categoryType?.name ?: "null"}")
            Log.d(TAG, "handleForegroundApp() mode=${_blockingMode.value} isWarningShowing=${isWarningScreenActive.get()}")
        }

        if (categoryType == CategoryType.USER_ALLOWED) {
            if (verbose) Log.d(TAG, "handleForegroundApp() → ALLOW (whitelisted) pkg=$packageName")
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "USER_WHITELIST")
        }

        val shouldBlock = evaluateBlockingRule(categoryType)
        if (!shouldBlock) {
            val reason = "CATEGORY_${categoryType?.name ?: "UNKNOWN"}"
            if (verbose) Log.d(TAG, "handleForegroundApp() → ALLOW ($reason) pkg=$packageName")
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, reason)
        }

        if (cooldownManager.isInCooldown(packageName)) {
            Log.i(TAG, "handleForegroundApp() → COOLDOWN pkg=$packageName")
            _lastDecision.value = BlockingDecision.CooldownActive
            return Pair(BlockingDecision.CooldownActive, "COOLDOWN_ACTIVE")
        }

        if (isWarningScreenActive.get()) {
            Log.i(TAG, "handleForegroundApp() → WARNING_ALREADY_SHOWING pkg=$packageName")
            _lastDecision.value = BlockingDecision.WarningAlreadyShowing
            return Pair(BlockingDecision.WarningAlreadyShowing, "WARNING_ALREADY_SHOWING")
        }

        cooldownManager.recordBlock(packageName)
        val appName = try {
            appDetector.getAppName(packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting app name for $packageName", e)
            packageName
        }
        val mode = _blockingMode.value
        val timeRemaining = currentSession.secondsRemaining

        try {
            distractionTracker.logDistraction(
                packageName = packageName,
                appName = appName,
                sessionId = currentSession.sessionId,
                blockingMode = mode.name,
                timeRemainingSeconds = timeRemaining
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error logging distraction for $packageName", e)
        }
        _distractionCountForSession.value += 1

        Log.i(TAG, "handleForegroundApp() → BLOCK pkg=$packageName app=$appName sessionId=${currentSession.sessionId}")
        launchWarningActivity(packageName, appName)

        _lastDecision.value = BlockingDecision.Block
        return Pair(BlockingDecision.Block, "NOT_WHITELISTED")
    }

    /**
     * Unconditional block path for activity-level (Shorts/Reels) and adult-URL
     * hits. Unlike [handleForegroundApp] this does NOT evaluate the category /
     * whitelist rules: the caller has already established that the *current
     * context* is blocked (e.g. YouTube is whitelisted but Shorts is not).
     * Routing those hits through handleForegroundApp used to return
     * USER_WHITELIST and silently allow the app.
     */
    @JvmOverloads
    fun forceBlock(
        packageName: String,
        appName: String,
        reason: String,
        standalone: Boolean = false
    ): Pair<BlockingDecision, String> {
        if (essentialSystemLayer.checkPackage(packageName) != null) {
            _lastDecision.value = BlockingDecision.Allowed
            return Pair(BlockingDecision.Allowed, "ESSENTIAL_SYSTEM")
        }
        if (cooldownManager.isInCooldown(packageName)) {
            _lastDecision.value = BlockingDecision.CooldownActive
            return Pair(BlockingDecision.CooldownActive, "COOLDOWN_ACTIVE")
        }
        if (isWarningScreenActive.get()) {
            _lastDecision.value = BlockingDecision.WarningAlreadyShowing
            return Pair(BlockingDecision.WarningAlreadyShowing, "WARNING_ALREADY_SHOWING")
        }
        Log.i(TAG, "forceBlock() → BLOCK pkg=$packageName app=$appName reason=$reason standalone=$standalone")
        cooldownManager.recordBlock(packageName)
        launchWarningActivity(packageName, appName, standalone, reason)
        _lastDecision.value = BlockingDecision.Block
        return Pair(BlockingDecision.Block, "FORCED_$reason")
    }

    private fun evaluateBlockingRule(category: CategoryType?): Boolean {
        val mode = _blockingMode.value
        val result = BlockingRules.shouldBlock(mode, category)
        if (verbose) {
            Log.d(TAG, "evaluateBlockingRule: category=${category ?: "null"}, mode=$mode, shouldBlock=$result")
        }
        return result
    }

    private fun launchWarningActivity(
        packageName: String,
        appLabel: String,
        standalone: Boolean = false,
        reason: String = ""
    ) {
        Log.i(TAG, "launchWarningActivity() pkg=$packageName app=$appLabel mode=${_blockingMode.value} standalone=$standalone reason=$reason")
        if (!isWarningScreenActive.compareAndSet(false, true)) {
            Log.i(TAG, "launchWarningActivity() SKIPPED — warning already showing")
            return
        }
        val intent = android.content.Intent(context, Class.forName("com.jainam.studentos.WarningActivity")).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("packageName", packageName)
            putExtra("appName", appLabel)
            putExtra("difficultyMode", _blockingMode.value.name)
            // Standalone = no focus session running (always-on surface block).
            // The block screen must not auto-dismiss on "no session" and should
            // eject to the home screen rather than the app's Focus tab.
            putExtra("standalone", standalone)
            // Reason lets the block screen avoid granting a re-entry "grace pass"
            // for surface hits (Shorts/Reels/URL) — that pass is what let the user
            // reopen Shorts right after a block and skip it.
            putExtra("reason", reason)
        }
        try {
            context.startActivity(intent)
            Log.i(TAG, "launchWarningActivity() OK — WarningActivity started")
            warningVisible = false
            val watchdog = Runnable {
                if (!warningVisible) {
                    Log.w(TAG, "launchWarningActivity() — block screen never became visible; releasing latch")
                    isWarningScreenActive.set(false)
                }
            }
            warningWatchdog?.let { mainHandler.removeCallbacks(it) }
            warningWatchdog = watchdog
            mainHandler.postDelayed(watchdog, WARNING_VISIBILITY_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "launchWarningActivity() FAILED — ${e::class.simpleName}: ${e.message}", e)
            isWarningScreenActive.set(false)
            disarmWarningWatchdog()
        }
    }

    fun getDistractionTracker(): DistractionTracker = distractionTracker
    fun getCooldownManager(): CooldownManager = cooldownManager

    fun resetWarningScreen() {
        isWarningScreenActive.set(false)
        disarmWarningWatchdog()
        Log.i(TAG, "resetWarningScreen() — isWarningScreenActive=false, next blocked app will trigger warning")
    }

    fun resetSessionCounters() {
        Log.i(TAG, "resetSessionCounters() from ${_distractionCountForSession.value} distractions")
        _distractionCountForSession.value = 0
        cooldownManager.reset()
        isWarningScreenActive.set(false)
        disarmWarningWatchdog()
    }

    fun cancel() {
        engineJob.cancel()
        cooldownManager.cancel()
    }

    // Decision matrix lives in BlockingRules (unit-tested).
}

package com.jainam.studentos.focusshield

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jainam.studentos.WarningActivity
import com.jainam.studentos.alarm.ThoughtNotifier
import com.jainam.studentos.focusshield.core.notification.NotificationHelper
import com.jainam.studentos.focusshield.data.AppCategory
import com.jainam.studentos.focusshield.data.AppDatabase
import com.jainam.studentos.focusshield.data.CategoryType
import com.jainam.studentos.focusshield.data.datastore.FocusPreferences
import com.jainam.studentos.focusshield.feature.blocking.BlockingEngine
import com.jainam.studentos.focusshield.feature.blocking.BlockingMode
import com.jainam.studentos.focusshield.feature.blocking.BlockingRules
import com.jainam.studentos.focusshield.feature.blocking.EssentialSystemLayer
import com.jainam.studentos.focusshield.feature.blocking.ShortFormGuard
import com.jainam.studentos.focusshield.feature.blocking.ShortFormRules
import com.jainam.studentos.focusshield.feature.scanner.AppDetector
import com.jainam.studentos.focusshield.manager.FocusSessionManager
import com.jainam.studentos.focusshield.manager.FocusSessionState
import com.jainam.studentos.focusshield.service.DoNotDisturbService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class FocusShieldManager(context: Context) {

    interface Listener {
        fun onForegroundAppChanged(packageName: String, appName: String)
        fun onBlockedAppAttempt(packageName: String, appName: String, timestamp: Long)
        fun onSessionStateChanged(state: String)
    }

    /**
     * Fired once a start request has either armed blocking or definitively
     * failed. Without this the bridge resolved `success:true` before the list
     * persistence coroutine ran, so the UI could show a protected session that
     * blocked nothing.
     */
    fun interface StartReadyCallback {
        fun onReady(ready: Boolean, reason: String)
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val focusPreferences = FocusPreferences(appContext)
    private val database = AppDatabase.getDatabase(appContext)
    private val appCategoryDao = database.appCategoryDao()
    private val distractionDao = database.distractionDao()
    private val notificationHelper = NotificationHelper(appContext)
    private val dndService = DoNotDisturbService(appContext)
    private val essentialSystemLayer = EssentialSystemLayer(appContext)
    private val appDetector = AppDetector(appContext)
    private val focusSessionManager = FocusSessionManager.getInstance(appContext, dndService, startForeground = true)
    private val blockingEngine = BlockingEngine(
        context = appContext,
        appCategoryDao = appCategoryDao,
        distractionDao = distractionDao,
        appDetector = appDetector,
        sessionManager = focusSessionManager,
        essentialSystemLayer = essentialSystemLayer
    )

    private val isDetectorActive = java.util.concurrent.atomic.AtomicBoolean(false)
    // Break-blocking: detector stays semi-active during pauses to block always-blocked apps
    private val isBreakBlockerActive = java.util.concurrent.atomic.AtomicBoolean(false)
    // Serialises blocklist writes: ALWAYS_BLOCKED / BREAK_BLOCKED share the
    // packageName primary key, so two concurrent writes could interleave and
    // demote rows from one list into the other.
    private val blockListMutex = kotlinx.coroutines.sync.Mutex()

    private var selfPackageName = ""
    private var performGlobalActionImpl: ((Int) -> Boolean)? = null
    /** Provided by the accessibility service so the URL filters can read the address bar. */
    private var rootNodeProvider: (() -> android.view.accessibility.AccessibilityNodeInfo?)? = null
    private val urlScanTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Per-scan content-filter logs are debug-only; release builds stay quiet. */
    private val verboseLogs =
        (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    /** "package|activity" → last decision time, for event-burst debouncing. */
    private val recentEventKeys = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── Short-form video (Shorts / Reels) ────────────────────────────────────
    // YouTube Shorts and Instagram Reels never show up as their own activity, so
    // they are detected from the accessibility view tree instead. See
    // ShortFormRules for the ids and the false-positive traps.
    private val shortFormGuard = ShortFormGuard()
    private val shortFormScanTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val shortFormPlayerSeenAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** The short-form app currently in the foreground (for per-open budget rearm). */
    private var shortFormActivePkg: String? = null
    /**
     * Last visible "title-ish" text of the short-form player. YouTube/Instagram
     * pagers emit no TYPE_VIEW_SCROLLED, so a change in this signature is the
     * reliable "user moved to the next short/reel" signal.
     */
    private val shortFormSignature = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val shortFormSignatureAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** When a surface app was last left — used to decide if the budget re-arms. */
    private val shortFormLeftAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── "Are you on track?" check-in (outside focus sessions only) ───────────
    // Only one app is in the foreground at a time, so a single tracked package
    // avoids stale per-app timers lingering after the user switches away.
    @Volatile
    private var checkInPkg: String? = null
    @Volatile
    private var checkInStartAt: Long = 0L
    @Volatile
    private var checkInFired: Boolean = false

    // ── YouTube Study Mode ───────────────────────────────────────────────────
    private val studyModeOverlayActive = java.util.concurrent.atomic.AtomicBoolean(false)
    private val studyModeScanTimes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    @Volatile
    private var studyModeCooldownUntil: Long = 0L
    private val listeners = ArrayList<Listener>()
    private var foregroundPollerJob: Job? = null
    private var checkInPollerJob: Job? = null
    private var breakPollerJob: Job? = null
    private var reconnectJob: Job? = null
    /** In-flight DataStore/Room rehydration. Start requests wait for it (fix C). */
    private var restoreJob: Job? = null
    @Volatile
    private var restoreCompleted = false
    /** Bumped by every startSession() so an in-flight restore cannot clobber it. */
    private val startGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private var sessionCompletionListenerRegistered = false
    private var sessionStateObserverActive = false
    private var categoryObserverActive = false
    private var lastEventPkg: String? = null
    private var lastEventTime: Long = 0L
    private var lastEventClassName: String? = null
    /** Timestamp of the last accessibility event — used to idle the fallback poller. */
    @Volatile
    private var lastAccessibilityEventAt: Long = 0L

    companion object {
        private const val TAG = "[FocusShield] FocusShieldManager"
        // The poller is only a fallback for phones that drop accessibility events.
        // While events are flowing it does no work at all (see startForegroundPoller).
        private const val POLLER_INTERVAL_MS = 12_000L
        /** If an accessibility event arrived this recently, skip the poll tick. */
        private const val POLLER_EVENT_GRACE_MS = 9_000L
        /** Debounce identical package+activity bursts on the event path. */
        private const val EVENT_DEBOUNCE_MS = 250L
        // Break poller runs less frequently (5s) since it only checks a small list.
        private const val BREAK_POLLER_INTERVAL_MS = 5_000L
        // Grace window right after a warning is dismissed: prevents the dismissal
        // itself from instantly re-triggering the warning for the same package
        // (the 500ms block cooldown is cleared on dismiss, see onWarningDismissed).
        // Must stay comfortably above POLLER_INTERVAL_MS or the next poll fires a
        // second warning for the app the user just closed.
        private const val WARNING_DISMISS_GRACE_MS = 6_000L
        // Browser URL scanning is throttled: browsers emit content-change events
        // continuously and walking the view tree on every one would burn battery.
        private const val URL_SCAN_INTERVAL_MS = 900L
        private const val MAX_URL_SCAN_NODES = 220
        // A node whose whole text looks like a URL is treated as the address bar
        // (needed for browsers that expose no view ids).
        private val URL_SHAPE = Regex(
            "^(https?://)?[a-z0-9][a-z0-9.-]*\\.[a-z]{2,}(:\\d+)?(/\\S*)?$",
            RegexOption.IGNORE_CASE
        )
        // Walking the view tree for the short-form player is cheap but not free;
        // YouTube/Instagram emit content changes continuously while a video plays.
        // 300ms (was 600ms): fast swipes were landing inside the old window and
        // being missed, so the player got a whole extra short before the next
        // scan. 300ms still throttles battery-heavy apps enough.
        private const val SHORT_FORM_SCAN_INTERVAL_MS = 300L
        // How long a positive player sighting stays valid for scroll handling.
        private const val SHORT_FORM_PLAYER_MEMORY_MS = 2_500L
        // Back-press lands first, then the block screen is shown.
        private const val SHORT_FORM_BACK_TO_OVERLAY_MS = 280L
        /** Follow-up player scan after entering a surface app (fragment inflates late). */
        private const val SHORT_FORM_ENTRY_RECHECK_MS = 350L
        /**
         * How long the app must be left before the per-visit budget re-arms.
         * Switching away and straight back used to re-arm instantly, so the user
         * could "reopen for one more short" forever. Five minutes makes the
         * budget stick across a doom-scroll session.
         */
        private const val SHORT_FORM_REARM_AFTER_MS = 5 * 60 * 1000L
        private const val GLOBAL_ACTION_BACK = 2
        /** "Are you on track?" fires after this much continuous time in a distracting app. */
        private const val CHECK_IN_INTERVAL_MS = 10 * 60 * 1000L
        /** How often the outside-session check-in poller samples the foreground app. */
        private const val CHECK_IN_POLL_MS = 15_000L
        /** YouTube Study Mode: how often the YouTube view tree is re-classified. */
        private const val STUDY_MODE_SCAN_INTERVAL_MS = 500L
        /** After dismissing the Study Mode overlay, don't re-show for this long. */
        private const val STUDY_MODE_DISMISS_COOLDOWN_MS = 4_000L
        /** Debounce after auto-opening Search from the Home feed. */
        private const val STUDY_MODE_SEARCH_COOLDOWN_MS = 3_000L
        /** Cap the view-tree walk when collecting ids for classification. */
        private const val MAX_STUDY_MODE_SCAN_NODES = 400
        /** Cap view-tree walk for text-hint surfaces (Snapchat). */
        private const val MAX_TEXT_SCAN_NODES = 400
    }

    @JvmOverloads
    fun setup(
        selfPackageName: String,
        performGlobalAction: (Int) -> Boolean,
        launchWarning: ((String, String) -> Unit)? = null,
        getRootNode: (() -> android.view.accessibility.AccessibilityNodeInfo?)? = null
    ) {
        this.selfPackageName = selfPackageName
        this.performGlobalActionImpl = performGlobalAction
        this.rootNodeProvider = getRootNode

        notificationHelper.createNotificationChannels()
        Log.i(TAG, "FocusShieldManager initialized with EssentialSystemLayer + BlockingEngine")

        restoreNativeState()
        observeSessionStateChanges()
        observeCategoryChanges()
        startCheckInPoller()
    }

    /**
     * Keeps the engine's in-memory category cache in sync with Room. One Flow
     * observer replaces a DB read on every foreground change.
     */
    private fun observeCategoryChanges() {
        if (categoryObserverActive) return
        categoryObserverActive = true
        scope.launch {
            try {
                appCategoryDao.getAllCategoriesFlow().collect { rows ->
                    blockingEngine.applyCategorySnapshot(rows)
                }
            } catch (e: Exception) {
                Log.w(TAG, "category flow observer stopped", e)
            }
        }
    }

    /**
     * Re-binds an existing manager (created by the Capacitor plugin before the
     * accessibility service connected) to the real service callbacks.
     *
     * The service used to construct a second FocusShieldManager, so two engines
     * and two pollers existed; the bridge pointed at the newest one while the
     * plugin may have configured the older one. Reusing one instance keeps a
     * single authoritative engine.
     */
    fun attachAccessibilityCallbacks(
        selfPackageName: String,
        performGlobalAction: (Int) -> Boolean,
        getRootNode: (() -> android.view.accessibility.AccessibilityNodeInfo?)? = null
    ) {
        this.selfPackageName = selfPackageName
        this.performGlobalActionImpl = performGlobalAction
        this.rootNodeProvider = getRootNode
        startCheckInPoller()
        Log.i(TAG, "attachAccessibilityCallbacks() — reused existing manager, bound to the accessibility service")
    }

    /**
     * Watches the foreground app while no focus session is running so the
     * "Are you on track?" check-in fires after continuous use. Events alone are
     * not enough — a static video screen emits none — so this ticks on a timer.
     * Cheap: it just reads the foreground package every 15s and does nothing
     * unless a distracting app has been open long enough.
     */
    private fun startCheckInPoller() {
        if (checkInPollerJob?.isActive == true) return
        checkInPollerJob = scope.launch(Dispatchers.IO) {
            while (true) {
                delay(CHECK_IN_POLL_MS)
                try {
                    if (isDetectorActive.get() || isBreakBlockerActive.get()) continue
                    if (!blockingEngine.isCheckInEnabled) continue
                    if (checkInFired) continue
                    val pkg = getCurrentForegroundApp() ?: lastEventPkg ?: continue
                    if (pkg == selfPackageName) continue
                    handleCheckIn(pkg, System.currentTimeMillis())
                } catch (_: Exception) {
                    // Never let the poller die on a transient error.
                }
            }
        }
    }

    /**
     * Forwards FocusSessionManager state transitions (including pause/stop/complete
     * triggered directly from the native notification) to JS listeners so the
     * in-app timer stays authoritative-synced. distinctUntilChanged avoids the
     * per-second tick spam from the status flow.
     */
    private fun observeSessionStateChanges() {
        if (sessionStateObserverActive) return
        sessionStateObserverActive = true
        scope.launch {
            focusSessionManager.status
                .map { it.state }
                .distinctUntilChanged()
                .collect { st -> notifySessionState(st.name.lowercase()) }
        }
    }

    private fun restoreNativeState() {
        if (restoreJob?.isActive == true) {
            Log.i(TAG, "restoreNativeState() — already running, not starting a second restore")
            return
        }
        // A start request that lands while this restore is in flight must win:
        // restoring afterwards used to overwrite the fresh session's mode and
        // allowlistReadiness with the stale persisted values (fix C).
        val generationAtStart = startGeneration.get()
        restoreJob = scope.launch(Dispatchers.IO) {
            try {
                Log.i(TAG, "restoreNativeState() — rehydrating from DataStore + Room DB")
                focusSessionManager.restoreSessionState()

                if (startGeneration.get() != generationAtStart) {
                    Log.i(TAG, "restoreNativeState() — session started during restore, keeping the new session's state")
                    return@launch
                }

                // Restore the blocking mode too. Without this a cold start (process
                // death / reboot) silently downgraded STRICT and BREAK sessions to
                // STANDARD, so a restored break session blocked everything.
                val restoredMode = try {
                    focusPreferences.blockingModeFlow.first()
                } catch (e: Exception) {
                    Log.w(TAG, "restoreNativeState() — could not read blocking mode, using STANDARD", e)
                    BlockingMode.STANDARD
                }
                blockingEngine.setBlockingMode(restoredMode)
                Log.i(TAG, "restoreNativeState() — blocking mode restored: $restoredMode")
                // Prime the in-memory category cache used by the per-event path.
                blockingEngine.refreshCategoryCache()

                // Restore the Smart Content Blocker filters. They used to live only
                // in the engine's memory, so every cold start silently turned them
                // all off until the user reopened Deep Lock settings.
                try {
                    val restoredFilters = focusPreferences.contentFiltersFlow.first()
                    blockingEngine.setContentFilters(
                        restoredFilters.blockShorts,
                        restoredFilters.blockReels,
                        restoredFilters.blockAdultUrls,
                        restoredFilters.blockTikTok,
                        restoredFilters.blockSnapchat,
                        restoredFilters.youtubeStudyMode
                    )
                    blockingEngine.setBlockedWebsites(restoredFilters.blockedWebsites)
                    blockingEngine.setShortFormAllowance(restoredFilters.shortFormAllowance)
                    shortFormGuard.setAllowance(restoredFilters.shortFormAllowance)
                    Log.i(
                        TAG,
                        "restoreNativeState() — content filters restored: shorts=${restoredFilters.blockShorts} " +
                            "reels=${restoredFilters.blockReels} tiktok=${restoredFilters.blockTikTok} " +
                            "snapchat=${restoredFilters.blockSnapchat} adult=${restoredFilters.blockAdultUrls} " +
                            "websites=${restoredFilters.blockedWebsites.size} " +
                            "shortFormAllowance=${restoredFilters.shortFormAllowance}"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "restoreNativeState() — could not restore content filters", e)
                }

                val sessionState = focusSessionManager.status.value.state
                Log.i(TAG, "restoreNativeState() — session state after restore: $sessionState")

                // 24/7 blocks must survive a reboot even with no session running:
                // the accessibility gate uses this set to stay awake outside
                // sessions, and the list is advertised as "blocked even outside
                // focus sessions".
                val persistentBlocks = appCategoryDao.getAllCategories()
                    .filter {
                        it.categoryType == CategoryType.ALWAYS_BLOCKED ||
                            it.categoryType == CategoryType.BREAK_BLOCKED
                    }
                    .map { it.packageName }
                    .toSet()
                if (persistentBlocks.isNotEmpty()) {
                    blockingEngine.updateBreakBlockedPackages(persistentBlocks)
                    blockingEngine.setBreakBlockingEnabled(true)
                    Log.i(TAG, "restoreNativeState() — 24/7 block set restored: ${persistentBlocks.size}")
                }

                if (sessionState == FocusSessionState.ACTIVE || sessionState == FocusSessionState.PAUSED) {
                    val allCategories = appCategoryDao.getAllCategories()
                    val allowedCount = allCategories.count { it.categoryType == CategoryType.USER_ALLOWED }
                    val blockedCount = allCategories.count {
                        it.categoryType == CategoryType.ALWAYS_BLOCKED ||
                        it.categoryType == CategoryType.BREAK_BLOCKED
                    }
                    Log.i(
                        TAG,
                        "restoreNativeState() — whitelist size=$allowedCount, explicit blocks=$blockedCount, " +
                            "mode=$restoredMode"
                    )

                    // Only whitelist (STRICT) mode needs a non-empty allowlist.
                    // BLOCKLIST and BREAK sessions arm from the user's blocklist
                    // alone, so an empty allowlist must not disable them after a
                    // cold start. (This used to require allowedCount > 0.)
                    val effectivelyReady = BlockingRules.restoredArms(restoredMode, allowedCount)

                    blockingEngine.setAllowlistReady(effectivelyReady)

                    if (!effectivelyReady) {
                        Log.w(TAG, "restoreNativeState() — STRICT mode with empty allowlist, NOT arming blocking (fail-safe)")
                    } else if (sessionState == FocusSessionState.ACTIVE) {
                        // Rebuild the break set as well. After process death the
                        // engine has no break packages, so pausing a restored
                        // session used to let every 24/7-blocked app through.
                        val breakPkgs = allCategories
                            .filter { it.categoryType == CategoryType.ALWAYS_BLOCKED || it.categoryType == CategoryType.BREAK_BLOCKED }
                            .map { it.packageName }.toSet()
                        if (breakPkgs.isNotEmpty()) {
                            blockingEngine.updateBreakBlockedPackages(breakPkgs)
                            blockingEngine.setBreakBlockingEnabled(true)
                        }
                        isDetectorActive.set(true)
                        startForegroundPoller()
                        Log.i(TAG, "restoreNativeState() — detector activated, poller started, breakSet=${breakPkgs.size}")
                    } else if (sessionState == FocusSessionState.PAUSED) {
                        // Restore break-blocker state
                        val breakBlockedPkgs = allCategories
                            .filter { it.categoryType == CategoryType.ALWAYS_BLOCKED || it.categoryType == CategoryType.BREAK_BLOCKED }
                            .map { it.packageName }.toSet()
                        if (breakBlockedPkgs.isNotEmpty()) {
                            blockingEngine.updateBreakBlockedPackages(breakBlockedPkgs)
                            blockingEngine.setBreakBlockingEnabled(true)
                            isBreakBlockerActive.set(true)
                            startBreakPoller()
                        }
                    }
                } else {
                    Log.i(TAG, "restoreNativeState() — no active session, detector remains idle")
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreNativeState() — error during rehydration", e)
            } finally {
                restoreCompleted = true
                Log.i(TAG, "restoreNativeState() — ready (restoreCompleted=true)")
            }
        }
    }

    /**
     * Blocks a start request until the cold-start restore has finished, so the
     * restore's mode/filter/allowlist writes can never land *after* the new
     * session armed (fix C). Bounded: a wedged restore must not hang a start.
     */
    private suspend fun awaitRestoreReady() {
        val job = restoreJob ?: return
        if (restoreCompleted) return
        val finished = withTimeoutOrNull(2_000) { job.join() }
        if (finished == null) {
            Log.w(TAG, "awaitRestoreReady() — restore still running after 2s, arming anyway")
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            if (!listeners.contains(listener)) listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    fun startSession(
        durationMinutes: Int,
        mode: String,
        whitelist: Set<String>,
        strict: Boolean = false,
        breakBlockedPackages: Set<String> = emptySet(),
        onReady: StartReadyCallback? = null
    ) {
        Log.i(TAG, "startSession() duration=${durationMinutes}min mode=$mode whitelistSize=${whitelist.size} strict=$strict breakBlocked=${breakBlockedPackages.size}")

        // Any session started from now on outranks an in-flight restore.
        startGeneration.incrementAndGet()

        // Resolve + apply the mode BEFORE anything can bail out. It used to be
        // applied only at the end, so a fail-safe return left the engine on the
        // previous mode.
        val mappedMode = when {
            // Break sessions must never become a full focus lock.
            mapToBlockingMode(mode) == BlockingMode.BREAK -> BlockingMode.BREAK
            strict -> BlockingMode.STRICT
            else -> BlockingMode.STANDARD
        }
        val needsAllowlist = BlockingRules.requiresAllowlist(mappedMode)

        scope.launch {
            // Fix (C): never arm on top of a half-finished rehydration — it used
            // to write the old persisted mode/allowlist right after we armed.
            awaitRestoreReady()

            focusSessionManager.startSession(durationMinutes, strict)
            blockingEngine.setBlockingMode(mappedMode)

            var listsOk = true
            var mergedCount = 0
            try {
                val existingAllowed = appCategoryDao.getAllCategories()
                    .filter { it.categoryType == CategoryType.USER_ALLOWED }
                val existingPkgs = existingAllowed.map { it.packageName }.toSet()
                // Union: JS whitelist is authoritative, but never wipe native-only entries
                val mergedPkgs = existingPkgs + whitelist
                mergedCount = mergedPkgs.size
                if (mergedPkgs.isEmpty() && needsAllowlist) {
                    listsOk = false
                    Log.w(TAG, "startSession() STRICT mode with an EMPTY allowlist — not arming blocking (fail-safe)")
                } else if (mergedPkgs.isNotEmpty()) {
                    val newCategories = mergedPkgs.map { pkg ->
                        AppCategory(
                            packageName = pkg,
                            appName = appDetector.getAppName(pkg),
                            categoryType = CategoryType.USER_ALLOWED,
                            isSystemApp = false
                        )
                    }
                    appCategoryDao.replaceUserAllowedWhitelist(newCategories)
                    Log.i(TAG, "startSession() whitelist merged & persisted (${newCategories.size} packages, js=${whitelist.size})")
                }

                // Persist break-blocked apps. This replaces ONLY the BREAK_BLOCKED
                // rows — the ALWAYS_BLOCKED list written from the settings UI must
                // survive (it used to be wiped here). Packages already on the
                // always-blocked list are skipped: same packageName PK.
                blockListMutex.lock()
                val alwaysBlockedPkgs: Set<String>
                val breakOnly: Set<String>
                try {
                    alwaysBlockedPkgs = appCategoryDao.getAllCategories()
                        .filter { it.categoryType == CategoryType.ALWAYS_BLOCKED }
                        .map { it.packageName }
                        .toSet()
                    breakOnly = breakBlockedPackages - alwaysBlockedPkgs

                    val breakCategories = breakOnly.map { pkg ->
                        AppCategory(
                            packageName = pkg,
                            appName = appDetector.getAppName(pkg),
                            categoryType = CategoryType.BREAK_BLOCKED,
                            isSystemApp = false
                        )
                    }
                    appCategoryDao.replaceBreakBlockedList(breakCategories)
                    Log.i(TAG, "startSession() break-blocked list persisted: ${breakOnly.size} break-only (${breakBlockedPackages.size} requested)")
                } finally {
                    blockListMutex.unlock()
                }

                // Engine break set = ALWAYS_BLOCKED (from DB) ∪ BREAK_BLOCKED (from JS).
                // ALWAYS_BLOCKED apps must stay blocked during a pause as well.
                blockingEngine.updateBreakBlockedPackages(alwaysBlockedPkgs + breakOnly)
                blockingEngine.setBreakBlockingEnabled(
                    breakOnly.isNotEmpty() || alwaysBlockedPkgs.isNotEmpty()
                )
                blockingEngine.refreshCategoryCache()

            } catch (e: Exception) {
                if (needsAllowlist) listsOk = false
                Log.e(
                    TAG,
                    "startSession() list persistence failed — " +
                        if (needsAllowlist) "not arming blocking (fail-safe)" else "blocklist is unaffected",
                    e
                )
            }

            // BLOCKLIST mode works from the user's blocklist alone: an empty
            // allowlist is the normal state on a fresh install. Only whitelist
            // (STRICT) mode is unsafe without a non-empty allowlist.
            val ready = BlockingRules.startArms(mappedMode, mergedCount, listsOk)
            blockingEngine.setAllowlistReady(ready)
            if (!ready) {
                isDetectorActive.set(false)
                stopForegroundPoller()
                Log.w(TAG, "startSession() blocking NOT armed — STRICT mode requires a non-empty allowlist")
                onReady?.onReady(false, "strict_allowlist_empty")
                return@launch
            }

            // A cold start defers FocusSessionManager.startSession() until its
            // DataStore restore finishes. Arming the detector while the session
            // is still INACTIVE made every non-ALWAYS_BLOCKED app allowed even
            // though the UI said the session was protected.
            if (!awaitSessionActive()) {
                isDetectorActive.set(false)
                stopForegroundPoller()
                Log.w(TAG, "startSession() session did not become ACTIVE — blocking not armed")
                onReady?.onReady(false, "session_not_active")
                return@launch
            }

            isDetectorActive.set(true)
            startForegroundPoller()
            // Every focus session gets a fresh shorts/reels budget.
            shortFormGuard.reset()
            shortFormPlayerSeenAt.clear()
            shortFormScanTimes.clear()
            // Persist the mode so a cold start restores it.
            try {
                focusPreferences.setBlockingMode(mappedMode)
            } catch (e: Exception) {
                Log.w(TAG, "startSession() could not persist blocking mode", e)
            }
            Log.i(
                TAG,
                "startSession() blocking ready — isDetectorActive=true, mode=$mappedMode, " +
                    "allowlistReady=$ready, allowlistSize=${whitelist.size}, poller started"
            )
            onReady?.onReady(true, "")
        }
    }

    /**
     * Waits (briefly) for the focus session to reach ACTIVE. A cold start defers
     * the session until the DataStore restore completes, so arming immediately
     * would produce a "protected" session with no enforcement window.
     */
    private suspend fun awaitSessionActive(): Boolean {
        val state = focusSessionManager.status.value.state
        if (state == FocusSessionState.ACTIVE) return true
        if (state == FocusSessionState.PAUSED) return false
        return withTimeoutOrNull(3_000) {
            focusSessionManager.status.first { it.state == FocusSessionState.ACTIVE }
            true
        } ?: false
    }

    fun pauseSession() {
        Log.i(TAG, "pauseSession() — pausing detector, arming break-blocker")
        focusSessionManager.pauseSession()
        isDetectorActive.set(false)
        stopForegroundPoller()
        reconnectJob?.cancel()

        // A warning can still be on screen when the user pauses. Clear the flag so
        // the next blocked app after resume triggers a fresh warning instead of
        // hitting the stale WARNING_ALREADY_SHOWING path (which silently allows).
        blockingEngine.resetWarningScreen()

        // Start break-blocker if enabled
        if (blockingEngine.isBreakBlockingEnabled) {
            isBreakBlockerActive.set(true)
            startBreakPoller()
            Log.i(TAG, "pauseSession() — break-blocker armed")
        } else {
            isBreakBlockerActive.set(false)
            stopBreakPoller()
        }

        notifySessionState("paused")
        Log.i(TAG, "pauseSession() complete")
    }

    fun resumeSession() {
        Log.i(TAG, "resumeSession() — reactivating detector")
        focusSessionManager.resumeSession()

        // Stop break poller, arm main detector
        isBreakBlockerActive.set(false)
        stopBreakPoller()

        isDetectorActive.set(true)
        startForegroundPoller()
        notifySessionState("active")
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val currentPkg = getCurrentForegroundApp()
            if (currentPkg != null && currentPkg != selfPackageName && currentPkg != "com.jainam.studentos") {
                val isEssential = essentialSystemLayer.checkPackage(currentPkg) != null
                if (!isEssential) {
                    Log.i(TAG, "resumeSession() — foreground app '$currentPkg' may be blocked, triggering check")
                    val appName = appDetector.getAppName(currentPkg)
                    notifyForegroundChanged(currentPkg, appName)
                    val (decision, reason) = blockingEngine.handleForegroundApp(currentPkg)
                    if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                        notifyBlockedAttempt(currentPkg, appName, System.currentTimeMillis())
                    }
                }
            }
        }
        Log.i(TAG, "resumeSession() complete")
    }

    fun endSession() {
        Log.i(TAG, "endSession() — stopping session")
        focusSessionManager.stopSession()
        isDetectorActive.set(false)
        isBreakBlockerActive.set(false)
        stopForegroundPoller()
        stopBreakPoller()
        blockingEngine.resetSessionCounters()
        clearShortFormSessionState()
        Log.i(TAG, "endSession() complete")
    }

    fun shutdown() {
        Log.i(TAG, "shutdown() — cancelling scope and cleaning up")
        isDetectorActive.set(false)
        isBreakBlockerActive.set(false)
        stopForegroundPoller()
        stopBreakPoller()
        checkInPollerJob?.cancel()
        checkInPollerJob = null
        scope.coroutineContext[Job]?.cancel()
        Log.i(TAG, "shutdown() complete")
    }

    fun cancelSession() {
        Log.i(TAG, "cancelSession() — cancelling session")
        focusSessionManager.stopSession()
        isDetectorActive.set(false)
        isBreakBlockerActive.set(false)
        stopForegroundPoller()
        stopBreakPoller()
        blockingEngine.resetSessionCounters()
        clearShortFormSessionState()
        Log.i(TAG, "cancelSession() complete")
    }

    fun forceComplete() {
        Log.i(TAG, "forceComplete() — diagnostic force complete")
        focusSessionManager.completeSession()
        isDetectorActive.set(false)
        isBreakBlockerActive.set(false)
        stopForegroundPoller()
        stopBreakPoller()
        blockingEngine.resetSessionCounters()
        clearShortFormSessionState()
    }

    /** The shorts/reels budget is per session: clear it when the session ends. */
    private fun clearShortFormSessionState() {
        shortFormGuard.reset()
        shortFormPlayerSeenAt.clear()
        shortFormScanTimes.clear()
        shortFormSignature.clear()
        shortFormSignatureAt.clear()
        shortFormLeftAt.clear()
    }

    /** Grant a timed emergency pass so a blocked app is not re-blocked for the window. */
    fun grantEmergencyPass(packageName: String) {
        Log.i(TAG, "grantEmergencyPass() pkg=$packageName")
        blockingEngine.getCooldownManager().grantPass(packageName, 5 * 60 * 1000L)
    }

    /** Called by the block screen once it is actually on screen. */
    fun onWarningVisible() {
        blockingEngine.onWarningVisible()
    }

    @JvmOverloads
    fun onWarningDismissed(packageName: String? = null, grantGracePass: Boolean = true) {
        Log.i(TAG, "onWarningDismissed() — resetting warning screen flag, pkg=$packageName gracePass=$grantGracePass")
        blockingEngine.resetWarningScreen()

        // Clear the block cooldown for the dismissed package so re-opening it is
        // caught again by the next accessibility event / poll. For app-level
        // blocks a short grace pass is granted so the dismiss itself cannot
        // instantly re-open the warning (dialog loop). Surface blocks
        // (Shorts/Reels/URL) skip the pass — otherwise reopening the player
        // within the window slipped straight through the block.
        if (packageName != null) {
            blockingEngine.getCooldownManager().clearCooldown(packageName)
            if (grantGracePass) {
                blockingEngine.getCooldownManager().grantPass(packageName, WARNING_DISMISS_GRACE_MS)
            }
        }

        scope.launch {
            try {
                val currentPkg = getCurrentForegroundApp()
                if (currentPkg != null &&
                    currentPkg != selfPackageName &&
                    currentPkg != "com.jainam.studentos" &&
                    currentPkg != packageName
                ) {
                    val isEssential = essentialSystemLayer.checkPackage(currentPkg) != null
                    if (!isEssential) {
                        Log.i(TAG, "onWarningDismissed() — foreground app '$currentPkg' may need re-check")
                        val (decision, _) = blockingEngine.handleForegroundApp(currentPkg)
                        if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                            Log.i(TAG, "onWarningDismissed() — re-blocked $currentPkg")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "onWarningDismissed() re-check failed", e)
            }
        }
    }

    fun setMode(mode: String) {
        val blockingMode = mapToBlockingMode(mode)
        blockingEngine.setBlockingMode(blockingMode)
        scope.launch {
            try {
                focusPreferences.setBlockingMode(blockingMode)
            } catch (e: Exception) {
                Log.w(TAG, "setMode() could not persist blocking mode", e)
            }
        }
    }

    /**
     * Replaces the allow-list. [onDone] fires only after the Room write lands:
     * callers used to get `success:true` while the write was still in flight, so
     * a status poll right after a toggle could report stale enforcement.
     */
    fun updateWhitelist(whitelist: Set<String>, onDone: Runnable? = null) {
        scope.launch {
            try {
                val newCategories = whitelist.map { pkg ->
                    AppCategory(
                        packageName = pkg,
                        appName = appDetector.getAppName(pkg),
                        categoryType = CategoryType.USER_ALLOWED,
                        isSystemApp = false
                    )
                }
                appCategoryDao.replaceUserAllowedWhitelist(newCategories)
                blockingEngine.refreshCategoryCache()
                Log.i(TAG, "updateWhitelist() atomically updated — ${whitelist.size} packages")
            } catch (e: Exception) {
                Log.e(TAG, "updateWhitelist() failed", e)
            } finally {
                onDone?.run()
            }
        }
    }

    /** Update the list of apps that are always blocked (even during breaks). */
    fun updateAlwaysBlockedList(packages: Set<String>, onDone: Runnable? = null) {
        scope.launch {
            blockListMutex.lock()
            try {
                val newCategories = packages.map { pkg ->
                    AppCategory(
                        packageName = pkg,
                        appName = appDetector.getAppName(pkg),
                        categoryType = CategoryType.ALWAYS_BLOCKED,
                        isSystemApp = false
                    )
                }
                // REPLACE on the shared packageName PK promotes any BREAK_BLOCKED
                // row of the same package to ALWAYS_BLOCKED.
                appCategoryDao.replaceAlwaysBlockedList(newCategories)

                // Drop BREAK_BLOCKED rows that are now ALWAYS_BLOCKED: one row per
                // package (PK), and ALWAYS_BLOCKED is the stronger category.
                val remainingBreak = appCategoryDao.getBreakBlockedCategories()
                    .filter { it.packageName !in packages }
                appCategoryDao.replaceBreakBlockedList(remainingBreak)

                val breakPkgs = remainingBreak.map { it.packageName }.toSet()
                blockingEngine.updateBreakBlockedPackages(packages + breakPkgs)
                blockingEngine.setBreakBlockingEnabled(packages.isNotEmpty() || breakPkgs.isNotEmpty())
                blockingEngine.refreshCategoryCache()
                Log.i(TAG, "updateAlwaysBlockedList() updated — ${packages.size} always, ${breakPkgs.size} break-only")
            } catch (e: Exception) {
                Log.e(TAG, "updateAlwaysBlockedList() failed", e)
            } finally {
                blockListMutex.unlock()
                onDone?.run()
            }
        }
    }

    /**
     * Update the list of apps that stay blocked during breaks. Replaces ONLY the
     * BREAK_BLOCKED rows so the ALWAYS_BLOCKED list written elsewhere survives.
     */
    fun updateBreakBlockedList(packages: Set<String>, onDone: Runnable? = null) {
        scope.launch {
            blockListMutex.lock()
            try {
                // ALWAYS_BLOCKED wins: those packages must not be demoted to
                // BREAK_BLOCKED (they share the packageName primary key).
                val alwaysBlocked = appCategoryDao.getAllCategories()
                    .filter { it.categoryType == CategoryType.ALWAYS_BLOCKED }
                    .map { it.packageName }
                    .toSet()
                val breakOnly = packages - alwaysBlocked

                val newCategories = breakOnly.map { pkg ->
                    AppCategory(
                        packageName = pkg,
                        appName = appDetector.getAppName(pkg),
                        categoryType = CategoryType.BREAK_BLOCKED,
                        isSystemApp = false
                    )
                }
                appCategoryDao.replaceBreakBlockedList(newCategories)
                blockingEngine.updateBreakBlockedPackages(alwaysBlocked + breakOnly)
                blockingEngine.setBreakBlockingEnabled(breakOnly.isNotEmpty() || alwaysBlocked.isNotEmpty())
                blockingEngine.refreshCategoryCache()
                Log.i(TAG, "updateBreakBlockedList() updated — ${breakOnly.size} break-only, ${alwaysBlocked.size} always")
            } catch (e: Exception) {
                Log.e(TAG, "updateBreakBlockedList() failed", e)
            } finally {
                blockListMutex.unlock()
                onDone?.run()
            }
        }
    }

    /**
     * Update the session-only blocklist: apps blocked while a focus session is
     * ACTIVE and allowed again the moment it ends. Writes CategoryType.BLOCKED
     * rows only — the 24/7 (ALWAYS_BLOCKED) and break (BREAK_BLOCKED) lists are
     * never touched. Packages already on a stronger list keep the stronger
     * category (one row per package, ALWAYS/BREAK win).
     */
    fun updateSessionBlockedList(packages: Set<String>, onDone: Runnable? = null) {
        scope.launch {
            blockListMutex.lock()
            try {
                val stronger = appCategoryDao.getAllCategories()
                    .filter {
                        it.categoryType == CategoryType.ALWAYS_BLOCKED ||
                            it.categoryType == CategoryType.BREAK_BLOCKED
                    }
                    .map { it.packageName }
                    .toSet()
                val sessionOnly = packages - stronger

                val newCategories = sessionOnly.map { pkg ->
                    AppCategory(
                        packageName = pkg,
                        appName = appDetector.getAppName(pkg),
                        categoryType = CategoryType.BLOCKED,
                        isSystemApp = false
                    )
                }
                appCategoryDao.replaceBlockedList(newCategories)
                blockingEngine.refreshCategoryCache()
                Log.i(
                    TAG,
                    "updateSessionBlockedList() updated — ${sessionOnly.size} session-only " +
                        "(${packages.size} requested, ${stronger.size} stronger lists)"
                )
            } catch (e: Exception) {
                Log.e(TAG, "updateSessionBlockedList() failed", e)
            } finally {
                blockListMutex.unlock()
                onDone?.run()
            }
        }
    }

    /** Push the user's content-filter choices into the blocking engine and persist them. */
    @JvmOverloads
    fun setContentFilters(
        blockShorts: Boolean,
        blockReels: Boolean,
        blockAdultUrls: Boolean,
        blockedWebsites: Set<String> = emptySet(),
        shortFormAllowance: Int = ShortFormGuard.DEFAULT_ALLOWANCE,
        blockTikTok: Boolean = false,
        blockSnapchat: Boolean = false,
        youtubeStudyMode: Boolean = false
    ) {
        blockingEngine.setContentFilters(
            blockShorts, blockReels, blockAdultUrls, blockTikTok, blockSnapchat, youtubeStudyMode
        )
        blockingEngine.setBlockedWebsites(blockedWebsites)
        blockingEngine.setShortFormAllowance(shortFormAllowance)
        shortFormGuard.setAllowance(shortFormAllowance)
        Log.i(
            TAG,
            "setContentFilters() shorts=$blockShorts reels=$blockReels tiktok=$blockTikTok " +
                "snapchat=$blockSnapchat studyMode=$youtubeStudyMode adultUrls=$blockAdultUrls " +
                "websites=${blockedWebsites.size} shortFormAllowance=$shortFormAllowance"
        )

        // Persist so a process death / reboot keeps enforcing the user's choices.
        scope.launch(Dispatchers.IO) {
            try {
                focusPreferences.setContentFilters(
                    com.jainam.studentos.focusshield.data.datastore.ContentFilterPrefs(
                        blockShorts = blockShorts,
                        blockReels = blockReels,
                        blockTikTok = blockTikTok,
                        blockSnapchat = blockSnapchat,
                        youtubeStudyMode = youtubeStudyMode,
                        blockAdultUrls = blockAdultUrls,
                        blockedWebsites = blockedWebsites,
                        shortFormAllowance = shortFormAllowance
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "setContentFilters() — could not persist filters", e)
            }
        }
    }

    /** Snapshot of the currently enforced filters (used by the settings screen). */
    fun getContentFilters(): Map<String, Any> = mapOf(
        "blockShorts" to blockingEngine.shortsFilterEnabled,
        "blockReels" to blockingEngine.reelsFilterEnabled,
        "blockTikTok" to blockingEngine.tikTokFilterEnabled,
        "blockSnapchat" to blockingEngine.snapchatFilterEnabled,
        "youtubeStudyMode" to blockingEngine.studyModeFilterEnabled,
        "blockAdultUrls" to blockingEngine.adultFilterEnabled,
        "blockedWebsites" to blockingEngine.blockedWebsiteSnapshot(),
        "urlBlockingActive" to blockingEngine.isUrlBlockingActive,
        "shortFormAllowance" to blockingEngine.shortFormAllowanceValue,
        "checkInEnabled" to blockingEngine.isCheckInEnabled
    )

    /** Update break-blocking preference. */
    fun setBreakBlockingEnabled(enabled: Boolean) {
        blockingEngine.setBreakBlockingEnabled(enabled)
        Log.i(TAG, "setBreakBlockingEnabled($enabled)")
    }

    /** Enable/disable YouTube Study Mode (gate the Home feed). */
    fun setYoutubeStudyMode(enabled: Boolean) {
        blockingEngine.setYoutubeStudyMode(enabled)
        Log.i(TAG, "setYoutubeStudyMode($enabled)")
    }

    /** Enable/disable the "Are you on track?" check-in (driven by Motivation toggle). */
    fun setCheckInEnabled(enabled: Boolean) {
        blockingEngine.setCheckInEnabled(enabled)
        if (!enabled) resetCheckInTracking()
        Log.i(TAG, "setCheckInEnabled($enabled)")
    }

    fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        if (event == null) return

        // The shield observes the system while a focus session is running (or
        // during a protected break). It also observes when the user has 24/7
        // blocked apps *or* any always-on content filter (Shorts/Reels/TikTok/
        // Snapchat/websites): those are advertised as working whenever the app is
        // open, so the old session-only early return made them unreachable.
        val shieldActive = isDetectorActive.get() || isBreakBlockerActive.get()
        val alwaysOnContent = blockingEngine.hasAlwaysOnContent
        if (!shieldActive && !alwaysOnContent && !blockingEngine.hasBreakBlockedPackages) {
            return
        }
        lastAccessibilityEventAt = System.currentTimeMillis()

        val urlFiltersEnabled = blockingEngine.isUrlBlockingActive

        val packageName = event.packageName?.toString() ?: return
        if (packageName.isBlank()) return
        if (packageName == selfPackageName) return

        val className = event.className?.toString()
        val now = System.currentTimeMillis()

        // ── Content-change events: browsers and short-form video ──────────────
        // Browsers expose the address bar through TYPE_WINDOW_CONTENT_CHANGED.
        // Shorts/Reels only ever show up here (they are fragments of the host
        // activity, so no window-state change is emitted). Both are high-volume,
        // so they are throttled per package and never fall through into the
        // app-level blocking path. Surface filters run whenever a session is on
        // OR an always-on content filter is enabled — the "always-on but lazy"
        // model; 24/7 app blocks still do not read page content.
        if (event.eventType != android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val surfaceFiltersActive = isDetectorActive.get() || alwaysOnContent
            if (!surfaceFiltersActive) return
            if (event.eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SCROLLED) {
                handleShortFormScroll(packageName, now)
            } else {
                handleShortFormVisibility(packageName, now)
            }
            if (urlFiltersEnabled && blockingEngine.isBrowserPackage(packageName)) {
                checkBrowserUrl(packageName, event, now)
            }
            // Check-in timer also needs foreground/content ticks outside sessions.
            handleCheckIn(packageName, now)
            handleYouTubeStudyMode(packageName, now)
            return
        }

        // FIX: Differentiate by class name as well as package name for activity-level blocking.
        // This allows blocking YouTube Shorts even if YouTube is whitelisted.
        // Debounce per package+activity: one screen can emit several window-state
        // events in a burst, and the decision only needs to run once per change.
        val eventKey = packageName + '|' + (className ?: "")
        val previous = recentEventKeys[eventKey] ?: 0L
        if (now - previous < EVENT_DEBOUNCE_MS) {
            return
        }
        recentEventKeys[eventKey] = now
        if (recentEventKeys.size > 64) {
            recentEventKeys.entries.removeIf { now - it.value > 5_000L }
        }
        lastEventPkg = packageName
        lastEventTime = now
        lastEventClassName = className

        val allowReason = essentialSystemLayer.checkPackage(packageName)
        if (allowReason != null) {
            return
        }

        // Entering the Shorts/Reels player can also surface as a window-state
        // change on some builds — check before the async block path so the
        // player is registered as an active "visit".
        if (isDetectorActive.get() || alwaysOnContent) {
            handleShortFormVisibility(packageName, now)
            // The window-state event often arrives a frame before the player
            // fragment is inflated, so the first scan finds no player and the
            // user sees a short play. Re-check once shortly after so Shorts/
            // Reels block on entry, not on the second swipe.
            if (ShortFormRules.surfaceFor(packageName) != null) {
                scope.launch {
                    delay(SHORT_FORM_ENTRY_RECHECK_MS)
                    try {
                        handleShortFormVisibility(packageName, System.currentTimeMillis())
                    } catch (_: Exception) {
                        // Node went stale; the next event will retry.
                    }
                }
            }
        }

        // Foreground change is the natural reset point for the check-in timer and
        // for re-arming the short-form budget on a fresh app open.
        maybeRearmShortFormOnAppSwitch(packageName)
        handleCheckIn(packageName, now)
        handleYouTubeStudyMode(packageName, now)

        scope.launch {
            try {
                val appName = appDetector.getAppName(packageName)

                // Activity-level blocks (Shorts, Reels, TikTok, Snapchat) apply
                // even for whitelisted apps, and now also outside sessions when
                // the matching always-on filter is on.
                val activityBlocked = (isDetectorActive.get() || alwaysOnContent) &&
                    blockingEngine.isActivityBlocked(packageName, className)

                if (activityBlocked) {
                    notifyForegroundChanged(packageName, appName)
                    Log.i(TAG, "onAccessibilityEvent: activity-level block triggered for $packageName/$className")
                    // forceBlock: bypasses category rules — the parent app may be
                    // whitelisted (YouTube/Instagram) while this activity is not.
                    val standalone = !isDetectorActive.get() && !isBreakBlockerActive.get()
                    val (decision, _) = blockingEngine.forceBlock(packageName, appName, "ACTIVITY", standalone)
                    if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                        notifyBlockedAttempt(packageName, appName, now)
                        refreshTodayDistractions()
                    }
                    return@launch
                }

                // Website filters: user block list + NSFW domains. Runs during a
                // session / protected break, or whenever the always-on content
                // filters are enabled (browsers are a target surface).
                if ((shieldActive || alwaysOnContent) && urlFiltersEnabled &&
                    blockingEngine.isBrowserPackage(packageName)
                ) {
                    val hit = findBlockedUrl(event)
                    if (hit != null) {
                        Log.i(TAG, "onAccessibilityEvent: blocked website in $packageName → $hit")
                        notifyForegroundChanged(packageName, "$appName (Blocked website)")
                        val standalone = !isDetectorActive.get() && !isBreakBlockerActive.get()
                        val (decision, _) = blockingEngine.forceBlock(
                            packageName, "$appName (Blocked website)", "URL", standalone
                        )
                        if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                            notifyBlockedAttempt(packageName, "$appName (Blocked website)", now)
                            refreshTodayDistractions()
                        }
                        return@launch
                    }
                }

                notifyForegroundChanged(packageName, appName)

                val (decision, reason) = blockingEngine.handleForegroundApp(packageName)
                when (decision) {
                    is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block -> {
                        val timestamp = System.currentTimeMillis()
                        notifyBlockedAttempt(packageName, appName, timestamp)
                        refreshTodayDistractions()
                    }
                    else -> {}
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing accessibility event for $packageName", e)
            }
        }
    }

    // ── Short-form video (Shorts / Reels) ────────────────────────────────────

    /**
     * Checks whether the short-form player of [packageName] is on screen and,
     * when it is, applies the per-session budget. Runs on the accessibility
     * thread, throttled per package.
     */
    private fun handleShortFormVisibility(packageName: String, now: Long) {
        val surface = ShortFormRules.surfaceFor(packageName) ?: return
        if (!blockingEngine.isShortFormEnabledFor(packageName)) return

        val lastScan = shortFormScanTimes[packageName] ?: 0L
        if (now - lastScan < SHORT_FORM_SCAN_INTERVAL_MS) return
        shortFormScanTimes[packageName] = now

        val root = try { rootNodeProvider?.invoke() } catch (e: Exception) {
            Log.w(TAG, "shortForm: could not read the accessibility root", e)
            null
        } ?: return

        val player = findVisibleShortFormPlayer(root, surface)
        if (player == null) {
            shortFormGuard.onPlayerGone(packageName)
            return
        }

        // Re-arm the budget only after a real absence (not a quick app switch).
        // Sessions keep their budget for the whole session.
        if (!isDetectorActive.get()) {
            val leftAt = shortFormLeftAt.remove(packageName)
            if (leftAt != null && now - leftAt > SHORT_FORM_REARM_AFTER_MS) {
                shortFormGuard.rearm(packageName)
            }
        } else {
            shortFormLeftAt.remove(packageName)
        }

        shortFormActivePkg = packageName
        shortFormPlayerSeenAt[packageName] = now
        val allowed = shortFormGuard.onPlayerVisible(packageName, now)
        if (verboseLogs) {
            Log.d(
                TAG,
                "shortForm: ${surface.label} player visible allowed=$allowed " +
                    "remaining=${shortFormGuard.remainingFor(packageName)}"
            )
        }
        if (!allowed) {
            blockShortForm(packageName, surface.label)
            return
        }

        // Swipe detection without TYPE_VIEW_SCROLLED: the player's own title text
        // changes to the next short/reel. A real change (not a like-count tick)
        // means the user moved on → count it as a navigation scroll.
        val signature = shortFormContentSignature(player)
        if (signature.isNotEmpty()) {
            val previous = shortFormSignature[packageName]
            if (!previous.isNullOrEmpty() && previous != signature) {
                if (shortFormGuard.onPlayerScroll(packageName, now)) {
                    Log.i(TAG, "shortForm: new ${surface.label} detected via title change → blocking")
                    shortFormSignature.remove(packageName)
                    shortFormSignatureAt.remove(packageName)
                    shortFormPlayerSeenAt.remove(packageName)
                    blockShortForm(packageName, surface.label)
                    return
                }
            }
            shortFormSignature[packageName] = signature
            shortFormSignatureAt[packageName] = now
        }
    }

    /**
     * Longest text/content-description inside the player, trimmed. Titles and
     * channel handles are long and change per short; like counts are short, so
     * the longest string is the most stable per-item signature.
     */
    private fun shortFormContentSignature(player: android.view.accessibility.AccessibilityNodeInfo): String {
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.addLast(player)
        var visited = 0
        var best: String = ""
        while (queue.isNotEmpty() && visited < 90) {
            val node = queue.removeFirst()
            visited++
            try {
                val raw = node.text?.toString() ?: node.contentDescription?.toString()
                val t = raw?.trim() ?: ""
                if (t.length in 6..120 && t.any { it.isLetter() } && t.length > best.length) {
                    best = t
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
            } catch (_: Exception) {
                // Stale node.
            }
        }
        return best
    }

    /**
     * Scrolling inside the player moves to the next short/reel. This is what
     * stops doom scrolling: the first one plays, the second one never appears.
     */
    private fun handleShortFormScroll(packageName: String, now: Long) {
        val surface = ShortFormRules.surfaceFor(packageName) ?: return
        if (!blockingEngine.isShortFormEnabledFor(packageName)) return

        val lastSeen = shortFormPlayerSeenAt[packageName] ?: 0L
        if (now - lastSeen > SHORT_FORM_PLAYER_MEMORY_MS) return

        // The scroll event carries no delta on every OEM; both "delta 0" and a
        // real delta mean the pager moved on.
        if (!shortFormGuard.onPlayerScroll(packageName, now)) return

        Log.i(TAG, "shortForm: scrolled to the next item in ${surface.label} → blocking")
        shortFormPlayerSeenAt.remove(packageName)
        blockShortForm(packageName, surface.label)
    }

    /**
     * Leaves the player first (so the next short never renders) and then shows
     * the block screen through the normal forced-block path.
     */
    private fun blockShortForm(packageName: String, label: String) {
        try {
            performGlobalActionImpl?.invoke(GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.w(TAG, "shortForm: back press failed", e)
        }
        scope.launch {
            delay(SHORT_FORM_BACK_TO_OVERLAY_MS)
            try {
                val appName = try { appDetector.getAppName(packageName) } catch (_: Exception) { packageName }
                val display = "$appName · $label"
                // Outside a session the shield is "always-on but lazy": there is no
                // session for the block screen to fall back to, so mark standalone.
                val standalone = !isDetectorActive.get() && !isBreakBlockerActive.get()
                val (decision, _) = blockingEngine.forceBlock(packageName, display, "SHORTFORM", standalone)
                if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                    notifyBlockedAttempt(packageName, display, System.currentTimeMillis())
                    refreshTodayDistractions()
                }
            } catch (e: Exception) {
                Log.e(TAG, "shortForm: could not show the block screen for $packageName", e)
            }
        }
    }

    /**
     * Finds the on-screen short-form player container.
     *
     * `isVisibleToUser` is mandatory: YouTube keeps the Shorts fragment inflated
     * behind the home feed with identical view ids and full-screen bounds, so an
     * id-only check would block the entire YouTube app.
     */
    private fun findVisibleShortFormPlayer(
        root: android.view.accessibility.AccessibilityNodeInfo,
        surface: ShortFormRules.Surface
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (surface.detection == ShortFormRules.Detection.TEXT_HINT) {
            return findTextHintSurface(root, surface)
        }

        val metrics = appContext.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val bounds = android.graphics.Rect()

        for (viewId in surface.playerViewIds) {
            val nodes = try {
                root.findAccessibilityNodeInfosByViewId(viewId)
            } catch (e: Exception) {
                Log.w(TAG, "shortForm: view id lookup failed for $viewId", e)
                null
            } ?: continue

            for (node in nodes) {
                try {
                    if (!node.isVisibleToUser) continue
                    node.getBoundsInScreen(bounds)
                    if (ShortFormRules.coversScreen(
                            screenWidth, screenHeight,
                            bounds.left, bounds.top, bounds.right, bounds.bottom
                        )
                    ) {
                        if (verboseLogs) Log.d(TAG, "shortForm: matched $viewId bounds=$bounds")
                        return node
                    }
                } catch (_: Exception) {
                    // Stale node — the window changed mid-walk.
                }
            }
        }
        return null
    }

    /**
     * Text-hint surface detection (Snapchat Spotlight + Stories).
     *
     * Snapchat exposes no stable view ids, so the only reliable signal is the
     * feed's own title in the header. The hint must sit in the top ~30% of the
     * screen: the bottom tab bar also says "Spotlight", so accepting it anywhere
     * would block the whole app the moment it opens.
     */
    private fun findTextHintSurface(
        root: android.view.accessibility.AccessibilityNodeInfo,
        surface: ShortFormRules.Surface
    ): android.view.accessibility.AccessibilityNodeInfo? {
        if (surface.textHints.isEmpty()) return null
        val metrics = appContext.resources.displayMetrics
        val topLimit = (metrics.heightPixels * 0.30f).toInt()
        val bounds = android.graphics.Rect()

        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TEXT_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                if (node.isVisibleToUser) {
                    val text = (
                        (node.text?.toString().orEmpty()) + " " +
                            (node.contentDescription?.toString().orEmpty())
                        ).lowercase()
                    if (text.isNotBlank() && surface.textHints.any { text.contains(it) }) {
                        node.getBoundsInScreen(bounds)
                        if (bounds.top in 0 until topLimit) {
                            if (verboseLogs) Log.d(TAG, "shortForm: text-hint '${surface.textHints}' matched bounds=$bounds")
                            return node
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.addLast(it) }
                }
            } catch (_: Exception) {
                // Stale node — window changed mid-walk.
            }
        }
        return null
    }

    // ── "Are you on track?" check-in ─────────────────────────────────────────

    /**
     * Outside sessions only: after CHECK_IN_INTERVAL_MS of continuous time in a
     * distracting app, show the calm "Are you on track?" overlay once per visit.
     * Enabled by the Motivation Engine toggle.
     */
    private fun handleCheckIn(packageName: String, now: Long) {
        // Only outside a focus session/break — sessions already enforce.
        if (isDetectorActive.get() || isBreakBlockerActive.get()) {
            resetCheckInTracking()
            return
        }
        if (!blockingEngine.isCheckInEnabled) return

        if (!blockingEngine.isDistractingShortFormPackage(packageName)) {
            // Left the distracting app: end the visit so the next open is fresh.
            if (checkInPkg != null && checkInPkg != packageName) resetCheckInTracking()
            return
        }

        // New foreground app → start a fresh window.
        if (checkInPkg != packageName) {
            checkInPkg = packageName
            checkInStartAt = now
            checkInFired = false
        }

        if (checkInFired) return
        if (now - checkInStartAt < CHECK_IN_INTERVAL_MS) return
        if (BlockingEngine.isWarningScreenActive.get()) return

        checkInFired = true
        Log.i(TAG, "checkIn: $packageName open ${(now - checkInStartAt) / 1000}s → posting 'Still on it?' notification")
        postCheckInNotification(packageName)
    }

    private fun resetCheckInTracking() {
        checkInPkg = null
        checkInStartAt = 0L
        checkInFired = false
    }

    // ── YouTube Study Mode ───────────────────────────────────────────────────

    /**
     * Gates YouTube's Home/browse feed when Study Mode is on. Search results and
     * the watch page are left completely alone — only the algorithmic feed is
     * covered. Runs on the accessibility thread, throttled.
     */
    private fun handleYouTubeStudyMode(packageName: String, now: Long) {
        if (packageName != "com.google.android.youtube") return
        if (!blockingEngine.isYoutubeStudyModeEnabled) return
        if (studyModeOverlayActive.get()) return
        if (BlockingEngine.isWarningScreenActive.get()) return
        if (now < studyModeCooldownUntil) return

        val last = studyModeScanTimes[packageName] ?: 0L
        if (now - last < STUDY_MODE_SCAN_INTERVAL_MS) return
        studyModeScanTimes[packageName] = now

        val root = try { rootNodeProvider?.invoke() } catch (e: Exception) {
            Log.w(TAG, "studyMode: could not read the accessibility root", e)
            null
        } ?: return

        val signals = collectYouTubeSignals(root)
        val surface = ShortFormRules.classifyYouTube(signals)
        if (verboseLogs) {
            Log.d(TAG, "studyMode: surface=$surface signals shorts=${signals.shortsPlayerVisible} " +
                "pivot=${signals.pivotBarVisible} tab=${signals.selectedTabLabel}")
        }
        if (surface == ShortFormRules.YouTubeSurface.HOME) {
            // Preferred: silently jump into Search so the feed is simply never
            // seen (no nagging popup). Fall back to the overlay only if the
            // Search control can't be clicked.
            if (openYouTubeSearch()) {
                studyModeCooldownUntil = now + STUDY_MODE_SEARCH_COOLDOWN_MS
                Log.i(TAG, "studyMode: Home detected → opened Search (no popup)")
            } else if (studyModeOverlayActive.compareAndSet(false, true)) {
                Log.i(TAG, "studyMode: Home detected, search unavailable → fallback overlay")
                launchStudyModeOverlay()
            }
        }
    }

    /**
     * Reads visibility-based signals for the active YouTube surface. Only nodes
     * that are actually visible count — YouTube preloads the watch container and
     * mini-player on the Home feed, so presence alone would misclassify Home as
     * the watch page and never gate it.
     */
    private fun collectYouTubeSignals(
        root: android.view.accessibility.AccessibilityNodeInfo
    ): ShortFormRules.YouTubeSignals {
        var shortsPlayerVisible = false
        var pivotBarVisible = false
        var homeLogoVisible = false
        var searchFieldVisible = false
        var selectedTabLabel: String? = null

        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_STUDY_MODE_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                val visible = node.isVisibleToUser
                val id = node.viewIdResourceName
                if (visible && id != null) {
                    when {
                        id.endsWith(":id/reel_watch_player") ||
                            id.endsWith(":id/reel_player_page_container") -> shortsPlayerVisible = true
                        id.endsWith(":id/pivot_bar") -> pivotBarVisible = true
                        id.endsWith(":id/youtube_logo") -> homeLogoVisible = true
                        id.endsWith(":id/search_edit_text") ||
                            id.endsWith(":id/search_bar") -> searchFieldVisible = true
                    }
                }
                if (visible && node.className?.toString()?.contains("EditText") == true) {
                    // The only free-text field on YouTube is the search box.
                    searchFieldVisible = true
                }
                if (visible && node.isSelected) {
                    val label = node.contentDescription?.toString() ?: node.text?.toString()
                    if (label != null) {
                        val l = label.lowercase()
                        if (l.startsWith("home") || l.startsWith("subscriptions") ||
                            l.startsWith("you") || l.startsWith("create") || l.startsWith("shorts")
                        ) {
                            selectedTabLabel = l
                        }
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
            } catch (_: Exception) {
                // Stale node.
            }
        }
        return ShortFormRules.YouTubeSignals(
            shortsPlayerVisible, pivotBarVisible, selectedTabLabel, homeLogoVisible, searchFieldVisible
        )
    }

    private fun launchStudyModeOverlay() {
        try {
            val intent = android.content.Intent(appContext, Class.forName("com.jainam.studentos.StudyModeActivity")).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            appContext.startActivity(intent)
            Log.i(TAG, "studyMode: StudyModeActivity launched")
        } catch (e: Exception) {
            Log.e(TAG, "studyMode: could not launch StudyModeActivity", e)
            studyModeOverlayActive.set(false)
        }
    }

    /** Called by StudyModeActivity when it dismisses (search or leave). */
    fun onStudyModeDismissed() {
        studyModeOverlayActive.set(false)
        studyModeCooldownUntil = System.currentTimeMillis() + STUDY_MODE_DISMISS_COOLDOWN_MS
        Log.i(TAG, "studyMode: dismissed — cooldown until ${studyModeCooldownUntil}")
    }

    /**
     * Brings the user into YouTube search after the overlay closes. Delayed so
     * the accessibility root is YouTube again (our overlay owns it while shown).
     */
    fun requestYouTubeSearch() {
        scope.launch {
            delay(400)
            val clicked = openYouTubeSearch()
            Log.i(TAG, "studyMode: requestYouTubeSearch() clicked=$clicked")
        }
    }

    /** Clicks YouTube's Search icon in the current view tree. */
    private fun openYouTubeSearch(): Boolean {
        val root = try { rootNodeProvider?.invoke() } catch (_: Exception) { null } ?: return false
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_STUDY_MODE_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                val desc = node.contentDescription?.toString()
                if (desc != null && desc.equals("Search", ignoreCase = true) && node.isVisibleToUser) {
                    if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                }
                for (i in 0 until node.childCount) node.getChild(i)?.let { queue.addLast(it) }
            } catch (_: Exception) {
                // Stale node.
            }
        }
        return false
    }

    /**
     * C1: motivation is notification-only. This used to launch a full-screen
     * `OnTrackActivity` that took over whatever the user was doing; it now posts
     * the same nudge through the silent `ThoughtNotifier` channel and leaves the
     * foreground app alone.
     */
    private fun postCheckInNotification(packageName: String) {
        try {
            // getAppName() is a suspend DAO lookup and this runs on the event
            // thread, so the copy stays generic (the per-app line comes from
            // MotivationEngine, which is pure).
            val body = MotivationEngine.getAppTitle(packageName)
            val entityId = "checkin-$packageName"
            ThoughtNotifier.show(appContext, entityId, "Still on it?", body)
            Log.i(TAG, "checkIn: notification posted for $packageName")
        } catch (e: Exception) {
            Log.w(TAG, "checkIn: could not post notification", e)
            // Let the next visit try again rather than latching the state.
            checkInFired = false
        }
    }

    /** Re-arm a short-form app's budget when the user switches away (outside sessions). */
    private fun maybeRearmShortFormOnAppSwitch(newPackage: String) {
        val previous = shortFormActivePkg ?: return
        if (previous == newPackage) return
        shortFormActivePkg = null
        shortFormSignature.remove(previous)
        shortFormSignatureAt.remove(previous)
        // Record when the app was left. The budget re-arms only after a real gap
        // (see SHORT_FORM_REARM_AFTER_MS) so switching away and straight back
        // does not grant a fresh short.
        shortFormLeftAt[previous] = System.currentTimeMillis()
    }

    // ── Website blocking helpers ─────────────────────────────────────────────

    /**
     * Throttled URL check for high-volume content-change events. Runs on the
     * accessibility thread, cheap when nothing matches.
     */
    private fun checkBrowserUrl(packageName: String, event: android.view.accessibility.AccessibilityEvent, now: Long) {
        val last = urlScanTimes[packageName] ?: 0L
        if (now - last < URL_SCAN_INTERVAL_MS) return
        urlScanTimes[packageName] = now

        val hit = findBlockedUrl(event) ?: return
        Log.i(TAG, "checkBrowserUrl: blocked website in $packageName → $hit")

        scope.launch {
            try {
                val appName = try { appDetector.getAppName(packageName) } catch (_: Exception) { packageName }
                notifyForegroundChanged(packageName, "$appName (Blocked website)")
                val standalone = !isDetectorActive.get() && !isBreakBlockerActive.get()
                val (decision, _) = blockingEngine.forceBlock(packageName, "$appName (Blocked website)", "URL", standalone)
                if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                    notifyBlockedAttempt(packageName, "$appName (Blocked website)", System.currentTimeMillis())
                    refreshTodayDistractions()
                }
            } catch (e: Exception) {
                Log.e(TAG, "checkBrowserUrl() failed for $packageName", e)
            }
        }
    }

    /**
     * Finds the first piece of browser text that matches a blocked website.
     * Event text/content-description is checked first (free); only when nothing
     * matches is the view tree walked to read the address bar.
     */
    private fun findBlockedUrl(event: android.view.accessibility.AccessibilityEvent): String? {
        try {
            event.text?.forEach { charSeq ->
                val text = charSeq?.toString()
                if (!text.isNullOrBlank() && blockingEngine.isUrlBlocked(text)) return text
            }
            event.contentDescription?.toString()?.let { description ->
                if (description.isNotBlank() && blockingEngine.isUrlBlocked(description)) return description
            }

            val root = rootNodeProvider?.invoke() ?: return null
            val addressBar = findAddressBarText(root) ?: return null
            return if (blockingEngine.isUrlBlocked(addressBar)) addressBar else null
        } catch (e: Exception) {
            Log.w(TAG, "findBlockedUrl() failed", e)
            return null
        }
    }

    /**
     * Bounded breadth-first walk looking for the browser address bar. Covers
     * Chrome (url_bar), Firefox (mozac_urlbar_text), Edge (url_bar),
     * Samsung Internet (location_bar_edit_text) and anything else whose view id
     * looks like a URL field. Browsers without ids (custom/OEM) fall back to the
     * first URL-shaped text node, which is how their address bar shows up in the
     * accessibility tree.
     */
    private fun findAddressBarText(root: android.view.accessibility.AccessibilityNodeInfo): String? {
        val queue = ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var firstUrlShaped: String? = null

        while (queue.isNotEmpty() && visited < MAX_URL_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            try {
                val viewId = node.viewIdResourceName?.lowercase()
                val nodeText = node.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: node.contentDescription?.toString()?.takeIf { it.isNotBlank() }

                if (viewId != null && (
                        viewId.contains("url") ||
                        viewId.contains("omnibox") ||
                        viewId.contains("address") ||
                        viewId.contains("location_bar") ||
                        viewId.contains("search_bar")
                    )
                ) {
                    if (nodeText != null) return nodeText
                }

                if (firstUrlShaped == null && nodeText != null && URL_SHAPE.matches(nodeText.trim())) {
                    firstUrlShaped = nodeText.trim()
                }

                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let { queue.add(it) }
                }
            } catch (_: Exception) {
                // Stale node (window changed mid-walk) — ignore and continue.
            }
        }
        return firstUrlShaped
    }

    fun isSessionActive(): Boolean = focusSessionManager.status.value.state == FocusSessionState.ACTIVE

    fun getActiveState(): String = when (focusSessionManager.status.value.state) {
        FocusSessionState.INACTIVE -> "idle"
        FocusSessionState.ACTIVE -> "active"
        FocusSessionState.PAUSED -> "paused"
        FocusSessionState.COMPLETED -> "completed"
    }

    fun getAppBlockerModeName(): String = blockingEngine.blockingMode.value.name

    fun getMode(): String = when (blockingEngine.blockingMode.value) {
        BlockingMode.STANDARD -> "hard"
        BlockingMode.STRICT -> "hardcore"
        BlockingMode.BREAK -> "break"
    }

    suspend fun getWhitelist(): Set<String> {
        return try {
            val categories = appCategoryDao.getAllCategories()
            categories.filter { it.categoryType == CategoryType.USER_ALLOWED || it.categoryType == CategoryType.STUDY }
                .map { it.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading whitelist", e)
            emptySet()
        }
    }

    suspend fun getAlwaysBlockedList(): Set<String> {
        return try {
            appCategoryDao.getAlwaysBlockedCategories().map { it.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading always-blocked list", e)
            emptySet()
        }
    }

    /** Session-only blocked packages (CategoryType.BLOCKED). */
    suspend fun getSessionBlockedList(): Set<String> {
        return try {
            appCategoryDao.getBlockedOnly().map { it.packageName }.toSet()
        } catch (e: Exception) {
            Log.e(TAG, "Error reading session-blocked list", e)
            emptySet()
        }
    }

    fun getTodayFocusMs(): Long {
        return focusSessionManager.status.value.let { status ->
            when (status.state) {
                FocusSessionState.ACTIVE -> {
                    val elapsed = System.currentTimeMillis() - status.startTimeMillis - status.totalPausedDurationMillis
                    elapsed.coerceAtLeast(0)
                }
                FocusSessionState.COMPLETED -> status.actualDurationMs()
                else -> 0L
            }
        }
    }

    private fun getCurrentForegroundApp(): String? {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val usm = appContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 5000, now)
            var lastPkg: String? = null
            var lastTime = 0L
            while (events.hasNextEvent()) {
                val event = android.app.usage.UsageEvents.Event()
                events.getNextEvent(event)
                if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp > lastTime) {
                        lastTime = event.timeStamp
                        lastPkg = event.packageName
                    }
                }
            }
            return lastPkg
        } catch (e: SecurityException) {
            Log.w(TAG, "getCurrentForegroundApp() — PACKAGE_USAGE_STATS not granted", e)
            return null
        } catch (e: Exception) {
            Log.e(TAG, "getCurrentForegroundApp() error", e)
            return null
        }
    }

    private fun startForegroundPoller() {
        foregroundPollerJob?.cancel()
        foregroundPollerJob = scope.launch(Dispatchers.IO) {
            // FIX: Immediate first check — no 10s dead zone on session start
            if (isDetectorActive.get()) {
                checkCurrentForegroundAndBlock()
            }
            while (isDetectorActive.get()) {
                delay(POLLER_INTERVAL_MS)
                if (!isDetectorActive.get()) break
                // Events are arriving: the event path already made the decision.
                if (System.currentTimeMillis() - lastAccessibilityEventAt < POLLER_EVENT_GRACE_MS) continue
                checkCurrentForegroundAndBlock()
            }
        }
    }

    private fun startBreakPoller() {
        breakPollerJob?.cancel()
        breakPollerJob = scope.launch(Dispatchers.IO) {
            // Immediate first check — same treatment as the foreground poller so a
            // paused session has no dead zone before the first break check.
            if (isBreakBlockerActive.get()) {
                checkBreakForegroundAndBlock()
            }
            while (isBreakBlockerActive.get()) {
                delay(BREAK_POLLER_INTERVAL_MS)
                if (!isBreakBlockerActive.get()) break
                checkBreakForegroundAndBlock()
            }
        }
    }

    private suspend fun checkBreakForegroundAndBlock() {
        try {
            val currentPkg = getCurrentForegroundApp()
            if (currentPkg != null && currentPkg != selfPackageName && currentPkg != "com.jainam.studentos") {
                val isEssential = essentialSystemLayer.checkPackage(currentPkg) != null
                if (!isEssential) {
                    val (decision, _) = blockingEngine.handleForegroundApp(currentPkg)
                    if (decision is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block) {
                        val appName = appDetector.getAppName(currentPkg)
                        notifyBlockedAttempt(currentPkg, appName, System.currentTimeMillis())
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Break poller error", e)
        }
    }

    private suspend fun checkCurrentForegroundAndBlock() {
        try {
            val currentPkg = getCurrentForegroundApp()
            if (currentPkg != null && currentPkg != selfPackageName && currentPkg != "com.jainam.studentos") {
                if (currentPkg == lastEventPkg && (System.currentTimeMillis() - lastEventTime) < 5000) {
                    // Already handled by accessibility event
                } else {
                    val isEssential = essentialSystemLayer.checkPackage(currentPkg) != null
                    if (!isEssential) {
                        val (decision, _) = blockingEngine.handleForegroundApp(currentPkg)
                        when (decision) {
                            is com.jainam.studentos.focusshield.feature.blocking.BlockingDecision.Block -> {
                                val appName = appDetector.getAppName(currentPkg)
                                notifyBlockedAttempt(currentPkg, appName, System.currentTimeMillis())
                            }
                            else -> {}
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Foreground poller error", e)
        }
    }

    private fun stopForegroundPoller() {
        foregroundPollerJob?.cancel()
        foregroundPollerJob = null
    }

    private fun stopBreakPoller() {
        breakPollerJob?.cancel()
        breakPollerJob = null
    }

    @Volatile
    private var cachedTodayDistractions = 0

    fun getTodayDistractions(): Int {
        return cachedTodayDistractions
    }

    private fun refreshTodayDistractions() {
        scope.launch {
            try {
                val dayStart = getDayStartMs()
                val dayEnd = dayStart + 86_400_000L
                val count = distractionDao.getDistractionCountForDay(dayStart, dayEnd)
                cachedTodayDistractions = count
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing today distractions", e)
            }
        }
    }

    fun getTotalFocusMs(): Long {
        return try {
            val sessions = focusSessionManager.status.value
            sessions.durationSeconds * 1000L
        } catch (e: Exception) {
            0L
        }
    }

    fun getSessionSnapshot(): Map<String, Any?> {
        return focusSessionManager.getSessionStateSnapshot()
    }

    fun checkPendingCompletions() {
        scope.launch(Dispatchers.IO) {
            val notifier = sessionCompletedNotifier
            if (notifier == null) {
                Log.w(TAG, "[FOCUS_INSTRUMENT] checkPendingCompletions — notifier not set, skipping consumption")
                return@launch
            }
            val pending = focusSessionManager.consumePendingCompletion() ?: return@launch
            try {
                notifier(pending)
            } catch (e: Exception) {
                Log.e(TAG, "checkPendingCompletions notifier failed", e)
            }
        }
    }

    @Synchronized
    fun registerSessionCompleteListener() {
        if (sessionCompletionListenerRegistered) return
        sessionCompletionListenerRegistered = true
        FocusSessionManager.addOnSessionCompleteCallback {
            isDetectorActive.set(false)
            isBreakBlockerActive.set(false)
            stopForegroundPoller()
            stopBreakPoller()
            blockingEngine.resetSessionCounters()
            clearShortFormSessionState()
            val snap = focusSessionManager.getSessionStateSnapshot()
            val data = HashMap<String, Any?>()
            data["sessionId"] = snap["sessionId"] ?: ""
            data["actualDurationMs"] = snap["actualDurationMs"] ?: 0L
            data["plannedDuration"] = snap["plannedDuration"] ?: 0L
            data["streak"] = snap["streak"] ?: 0
            data["startTime"] = snap["startTime"] ?: 0L
            data["endTime"] = snap["endTime"] ?: 0L

            val notifier = sessionCompletedNotifier
            if (notifier != null) {
                try {
                    notifier(data)
                } catch (e: Exception) {
                    Log.e(TAG, "sessionCompleted notifier failed", e)
                }
            } else {
                focusSessionManager.savePendingCompletion(
                    data["sessionId"] as String,
                    data["actualDurationMs"] as Long,
                    data["plannedDuration"] as Long,
                    data["streak"] as Int,
                    data["startTime"] as Long,
                    data["endTime"] as Long
                )
            }
        }
    }

    @Volatile
    private var sessionCompletedNotifier: ((Map<String, Any?>) -> Unit)? = null

    fun setSessionCompletedNotifier(notifier: ((Map<String, Any?>) -> Unit)?) {
        sessionCompletedNotifier = notifier
    }

    fun getSessionContext(): SessionContext? {
        val snap = focusSessionManager.getSessionStateSnapshot()
        if (snap["status"] == "inactive") return null
        return SessionContext(
            elapsedMinutes = ((snap["actualDurationMs"] as Long) / 60000).toInt(),
            remainingMinutes = ((snap["remainingMs"] as Long) / 60000).toInt(),
            totalMinutes = ((snap["plannedDuration"] as Long) / 60000).toInt(),
            interruptionCount = blockingEngine.distractionCountForSession.value,
            streak = (snap["streak"] as? Int) ?: 0
        )
    }

    data class SessionContext(
        val elapsedMinutes: Int,
        val remainingMinutes: Int,
        val totalMinutes: Int,
        val interruptionCount: Int,
        val streak: Int
    )

    private fun mapToBlockingMode(mode: String): BlockingMode {
        return when (mode.lowercase()) {
            "hardcore", "strict" -> BlockingMode.STRICT
            "break" -> BlockingMode.BREAK
            else -> BlockingMode.STANDARD
        }
    }

    private fun notifyForegroundChanged(packageName: String, appName: String) {
        synchronized(listeners) {
            for (l in listeners) l.onForegroundAppChanged(packageName, appName)
        }
    }

    private fun notifyBlockedAttempt(packageName: String, appName: String, timestamp: Long) {
        synchronized(listeners) {
            for (l in listeners) l.onBlockedAppAttempt(packageName, appName, timestamp)
        }
    }

    private fun notifySessionState(state: String) {
        synchronized(listeners) {
            for (l in listeners) l.onSessionStateChanged(state)
        }
    }

    private fun getDayStartMs(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

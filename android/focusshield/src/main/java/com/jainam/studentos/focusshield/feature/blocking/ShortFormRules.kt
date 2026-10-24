package com.jainam.studentos.focusshield.feature.blocking

/**
 * Short-form video ("doom scroll") surface detection.
 *
 * YouTube Shorts and Instagram Reels are **not** separate activities: Shorts is
 * hosted by `com.google.android.youtube.app.honeycomb.Shell$HomeActivity` and
 * Reels by `com.instagram.android.activity.MainTabActivity`. Matching activity
 * class names (what this engine used to do) can therefore never see them — the
 * player is a fragment inside the host app.
 *
 * The only reliable signal is the view hierarchy: the short-form player
 * container. Two traps make a naive "the id exists" check unusable:
 *
 *  1. YouTube keeps the Shorts fragment inflated behind the home feed, with the
 *     exact same `reel_*` view ids and identical full-screen bounds. Callers
 *     MUST require `AccessibilityNodeInfo.isVisibleToUser`.
 *  2. Instagram reuses `reel_viewer_*` ids for **Stories**, so only the
 *     `clips_viewer_*` ids (Reels) may be used here.
 *
 * Kept free of Android dependencies so it can be unit tested on the JVM.
 */
object ShortFormRules {

    /**
     * How the surface is recognised in the view tree.
     *
     *  - [Detection.VIEW_ID]: stable resource ids (YouTube / Instagram).
     *  - [Detection.TEXT_HINT]: no stable ids exist, so a full-bleed player is
     *    confirmed by matching visible content-description / text (Snapchat
     *    Spotlight + Stories). Chat and Snaps never match the hints, so they
     *    keep working.
     */
    enum class Detection { VIEW_ID, TEXT_HINT }

    data class Surface(
        val packageName: String,
        val label: String,
        /** Full view ids of the short-form player container, highest priority first. */
        val playerViewIds: List<String>,
        val detection: Detection = Detection.VIEW_ID,
        /** Lower-case substrings matched against visible text for TEXT_HINT surfaces. */
        val textHints: List<String> = emptyList()
    )

    private val SURFACES: Map<String, Surface> = listOf(
        Surface(
            packageName = "com.google.android.youtube",
            label = "YouTube Shorts",
            playerViewIds = listOf(
                "com.google.android.youtube:id/reel_watch_player",
                "com.google.android.youtube:id/reel_player_page_container",
                "com.google.android.youtube:id/reel_watch_fragment_root"
            )
        ),
        Surface(
            packageName = "com.instagram.android",
            label = "Instagram Reels",
            playerViewIds = listOf(
                "com.instagram.android:id/clips_viewer_view_pager",
                "com.instagram.android:id/clips_viewer_container",
                "com.instagram.android:id/clips_viewer_fragment_container"
            )
        ),
        Surface(
            packageName = "com.snapchat.android",
            label = "Snapchat Spotlight",
            playerViewIds = emptyList(),
            detection = Detection.TEXT_HINT,
            // "Spotlight" names the short-form feed; the Stories viewer surfaces
            // its own title. Chat and the camera screen match none of these.
            textHints = listOf("spotlight", "stories")
        )
    ).associateBy { it.packageName }

    /** The short-form surface for [packageName], or null when the app has none. */
    fun surfaceFor(packageName: String?): Surface? {
        if (packageName.isNullOrBlank()) return null
        return SURFACES[packageName]
    }

    /**
     * A full-bleed short-form player: at least 70% of the screen width and 55%
     * of its height. Guards against picking up a shelf preview or a PiP window.
     */
    fun coversScreen(
        screenWidth: Int,
        screenHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return false
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false
        return width >= screenWidth * MIN_WIDTH_RATIO && height >= screenHeight * MIN_HEIGHT_RATIO
    }

    /**
     * A scroll inside the short-form player moves to the next item. OEM builds
     * report either a real delta or 0, and both mean "next short/reel", so only
     * obviously tiny deltas are ignored.
     */
    fun isNavigationScroll(deltaY: Int): Boolean =
        deltaY == 0 || kotlin.math.abs(deltaY) >= MIN_SCROLL_DELTA

    /** Apps whose whole package is blocked when the Shorts filter is on. */
    val WHOLE_APP_SHORT_FORM_PACKAGES: Set<String> = setOf(
        "com.zhiliaoapp.musically",   // TikTok
        "com.ss.android.ugc.trill",   // TikTok (alt)
        "com.ss.android.ugc.aweme"    // Douyin
    )

    /** Which YouTube surface the current view tree represents. */
    enum class YouTubeSurface { HOME, SEARCH, WATCH, SHORTS, OTHER }

    /**
     * Visibility-based signals read from the live view tree. Presence alone is
     * useless: YouTube keeps the watch container and mini-player inflated on the
     * Home feed, so only *visible* nodes count.
     */
    data class YouTubeSignals(
        /** A short-form player is visible (Shorts is playing). */
        val shortsPlayerVisible: Boolean,
        /** The bottom tab bar (Home/Shorts/Create/Subscriptions/You) is visible. */
        val pivotBarVisible: Boolean,
        /** Lower-cased label of the selected bottom tab, or null if unknown. */
        val selectedTabLabel: String?,
        /** The YouTube wordmark is visible (only shown on the browse/home header). */
        val homeLogoVisible: Boolean = false,
        /** A search input / search bar is visible (search screen or results). */
        val searchFieldVisible: Boolean = false
    )

    /**
     * Classifies the YouTube surface from visibility signals.
     *
     * The bottom tab bar stays visible on the SEARCH screen too, so it alone is
     * not enough to call something "Home" (that misclassified search results as
     * home and popped the Study Mode gate over a search). Home is only claimed
     * when the YouTube wordmark is visible, the tab bar is present, the Home tab
     * is selected, and no search field is showing.
     *
     *  - Shorts playing → SHORTS.
     *  - Search field visible → OTHER (search screen/results — always allowed).
     *  - Logo + tab bar + Home selected → HOME (Study Mode gates this).
     *  - Anything else (watch page, subscriptions, you) → OTHER.
     */
    fun classifyYouTube(signals: YouTubeSignals): YouTubeSurface {
        if (signals.shortsPlayerVisible) return YouTubeSurface.SHORTS
        if (signals.searchFieldVisible) return YouTubeSurface.OTHER
        if (!signals.pivotBarVisible || !signals.homeLogoVisible) return YouTubeSurface.OTHER
        val tab = signals.selectedTabLabel
        return if (tab == null || tab.contains("home")) YouTubeSurface.HOME else YouTubeSurface.OTHER
    }

    private const val MIN_WIDTH_RATIO = 0.7f
    private const val MIN_HEIGHT_RATIO = 0.55f
    private const val MIN_SCROLL_DELTA = 24
}

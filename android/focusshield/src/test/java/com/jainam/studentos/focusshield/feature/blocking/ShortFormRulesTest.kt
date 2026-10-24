package com.jainam.studentos.focusshield.feature.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortFormRulesTest {

    @Test
    fun surfaceFor_knownShortFormApps() {
        assertEquals("YouTube Shorts", ShortFormRules.surfaceFor("com.google.android.youtube")?.label)
        assertEquals("Instagram Reels", ShortFormRules.surfaceFor("com.instagram.android")?.label)
    }

    @Test
    fun surfaceFor_ignoresOtherApps() {
        assertNull(ShortFormRules.surfaceFor(null))
        assertNull(ShortFormRules.surfaceFor(""))
        assertNull(ShortFormRules.surfaceFor("com.android.chrome"))
        assertNull(ShortFormRules.surfaceFor("com.instagram.android.clone"))
    }

    @Test
    fun youTubeSurface_usesPlayerIdsNotBroadSubstrings() {
        val surface = ShortFormRules.surfaceFor("com.google.android.youtube")
        assertNotNull(surface)
        // The ids observed on a real device for the Shorts player.
        assertTrue(surface!!.playerViewIds.contains("com.google.android.youtube:id/reel_watch_player"))
        // A bare "shorts" id must never be matched: YouTube's home feed would hit it.
        assertTrue(surface.playerViewIds.none { it.endsWith(":id/shorts") })
    }

    @Test
    fun instagramSurface_usesClipsIdsOnly() {
        val surface = ShortFormRules.surfaceFor("com.instagram.android")
        assertNotNull(surface)
        // reel_viewer_* is shared with Stories — blocking it would kill Stories too.
        assertTrue(surface!!.playerViewIds.none { it.contains("reel_viewer") })
        assertTrue(surface.playerViewIds.any { it.contains("clips_viewer") })
    }

    @Test
    fun coversScreen_acceptsFullBleedPlayer() {
        // Real measurement taken from a Samsung S25 while Shorts was playing.
        assertTrue(ShortFormRules.coversScreen(1080, 2340, 0, 103, 1080, 2151))
    }

    @Test
    fun coversScreen_rejectsShelfPreviewAndEmptyBounds() {
        // A Shorts shelf cell in the home feed: same ids, small box.
        assertFalse(ShortFormRules.coversScreen(1080, 2340, 0, 900, 1080, 1400))
        // Collapsed / detached node.
        assertFalse(ShortFormRules.coversScreen(1080, 2340, 0, 0, 0, 0))
        // Degenerate screen metrics.
        assertFalse(ShortFormRules.coversScreen(0, 0, 0, 0, 1080, 2151))
    }

    @Test
    fun isNavigationScroll_treatsUnknownDeltaAsNavigation() {
        assertTrue(ShortFormRules.isNavigationScroll(0)) // OEMs often report no delta
        assertTrue(ShortFormRules.isNavigationScroll(1080))
        assertTrue(ShortFormRules.isNavigationScroll(-1080))
        assertFalse(ShortFormRules.isNavigationScroll(5))
    }

    @Test
    fun wholeAppShortFormPackages_areTikTokFamilyOnly() {
        assertTrue(ShortFormRules.WHOLE_APP_SHORT_FORM_PACKAGES.contains("com.zhiliaoapp.musically"))
        assertFalse(ShortFormRules.WHOLE_APP_SHORT_FORM_PACKAGES.contains("com.google.android.youtube"))
    }

    @Test
    fun snapchatSurface_usesTextHintNotViewIds() {
        val surface = ShortFormRules.surfaceFor("com.snapchat.android")
        assertNotNull(surface)
        // Snapchat exposes no stable ids — detection is text-hint based.
        assertEquals(ShortFormRules.Detection.TEXT_HINT, surface!!.detection)
        assertTrue(surface.playerViewIds.isEmpty())
        assertTrue(surface.textHints.contains("spotlight"))
        assertTrue(surface.textHints.contains("stories"))
    }

    @Test
    fun viewIdSurfaces_areNotTextHint() {
        assertEquals(
            ShortFormRules.Detection.VIEW_ID,
            ShortFormRules.surfaceFor("com.google.android.youtube")!!.detection
        )
    }

    // ── YouTube Study Mode surface classification ────────────────────────────

    private fun signals(
        shorts: Boolean = false,
        pivot: Boolean = false,
        tab: String? = null,
        logo: Boolean = false,
        search: Boolean = false
    ) = ShortFormRules.YouTubeSignals(shorts, pivot, tab, logo, search)

    @Test
    fun classifyYouTube_shortsWins() {
        assertEquals(
            ShortFormRules.YouTubeSurface.SHORTS,
            ShortFormRules.classifyYouTube(signals(shorts = true, pivot = true, tab = "home", logo = true))
        )
    }

    @Test
    fun classifyYouTube_homeGated() {
        assertEquals(
            ShortFormRules.YouTubeSurface.HOME,
            ShortFormRules.classifyYouTube(signals(pivot = true, tab = "home", logo = true))
        )
    }

    @Test
    fun classifyYouTube_homeRequiresLogo() {
        // No YouTube wordmark (e.g. search header) → not home → allowed.
        assertEquals(
            ShortFormRules.YouTubeSurface.OTHER,
            ShortFormRules.classifyYouTube(signals(pivot = true, tab = "home", logo = false))
        )
    }

    @Test
    fun classifyYouTube_searchFieldAlwaysAllowed() {
        // Search results keep the bottom nav; a visible search field must win.
        assertEquals(
            ShortFormRules.YouTubeSurface.OTHER,
            ShortFormRules.classifyYouTube(signals(pivot = true, tab = "home", logo = true, search = true))
        )
    }

    @Test
    fun classifyYouTube_subscriptionsTabAllowed() {
        assertEquals(
            ShortFormRules.YouTubeSurface.OTHER,
            ShortFormRules.classifyYouTube(
                signals(pivot = true, tab = "subscriptions: new content is available", logo = true)
            )
        )
    }

    @Test
    fun classifyYouTube_watchAndNoPivotAllowed() {
        assertEquals(
            ShortFormRules.YouTubeSurface.OTHER,
            ShortFormRules.classifyYouTube(signals(pivot = false))
        )
    }
}

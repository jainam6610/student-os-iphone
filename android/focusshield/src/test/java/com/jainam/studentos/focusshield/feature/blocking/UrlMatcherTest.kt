package com.jainam.studentos.focusshield.feature.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlMatcherTest {

    @Test
    fun normalizeDomain_stripsSchemePathAndWww() {
        assertEquals("instagram.com", UrlMatcher.normalizeDomain("https://www.Instagram.com/reels/?x=1"))
        assertEquals("youtube.com", UrlMatcher.normalizeDomain("  youtube.com  "))
        assertEquals("reddit.com", UrlMatcher.normalizeDomain("http://reddit.com:8080/r/all"))
        assertEquals("example.co.uk", UrlMatcher.normalizeDomain("example.co.uk."))
    }

    @Test
    fun normalizeDomain_rejectsNonHostnames() {
        assertNull(UrlMatcher.normalizeDomain(""))
        assertNull(UrlMatcher.normalizeDomain("localhost"))
        assertNull(UrlMatcher.normalizeDomain("not a domain"))
        assertNull(UrlMatcher.normalizeDomain("..."))
    }

    @Test
    fun normalizeDomainList_dedupesAndCleans() {
        val list = UrlMatcher.normalizeDomainList(
            listOf("https://www.instagram.com/x", "instagram.com", "  ", "tiktok.com")
        )
        assertEquals(setOf("instagram.com", "tiktok.com"), list)
    }

    @Test
    fun hostMatches_coversSubdomains() {
        assertTrue(UrlMatcher.hostMatches("m.youtube.com", "youtube.com"))
        assertTrue(UrlMatcher.hostMatches("www.youtube.com", "youtube.com"))
        assertTrue(UrlMatcher.hostMatches("youtube.com", "youtube.com"))
    }

    @Test
    fun hostMatches_rejectsLookalikes() {
        assertFalse(UrlMatcher.hostMatches("notyoutube.com", "youtube.com"))
        assertFalse(UrlMatcher.hostMatches("youtube.com.evil.io", "youtube.com"))
        assertFalse(UrlMatcher.hostMatches("evil.io", "youtube.com"))
    }

    @Test
    fun isBlocked_matchesAddressBarUrls() {
        val blocked = setOf("instagram.com")
        assertTrue(UrlMatcher.isBlocked("instagram.com", blocked, false))
        assertTrue(UrlMatcher.isBlocked("https://www.instagram.com/stories/", blocked, false))
        assertTrue(UrlMatcher.isBlocked("m.instagram.com", blocked, false))
        assertFalse(UrlMatcher.isBlocked("https://google.com", blocked, false))
        assertFalse(UrlMatcher.isBlocked("", blocked, false))
        assertFalse(UrlMatcher.isBlocked(null, blocked, false))
    }

    @Test
    fun isBlocked_respectsAdultToggle() {
        val none = emptySet<String>()
        assertTrue(UrlMatcher.isBlocked("https://www.pornhub.com", none, true))
        assertFalse(UrlMatcher.isBlocked("https://www.pornhub.com", none, false))
    }

    @Test
    fun isBlocked_ignoresEverythingWhenNoRules() {
        assertFalse(UrlMatcher.isBlocked("https://instagram.com", emptySet(), false))
    }

    @Test
    fun extractHost_handlesBareAndSchemelessInput() {
        assertEquals("instagram.com", UrlMatcher.extractHost("instagram.com"))
        assertEquals("instagram.com", UrlMatcher.extractHost("https://instagram.com/x"))
        assertNull(UrlMatcher.extractHost("random text"))
    }
}

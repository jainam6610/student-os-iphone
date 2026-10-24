package com.jainam.studentos.focusshield.feature.blocking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ShortFormGuardTest {

    private lateinit var guard: ShortFormGuard

    private val yt = "com.google.android.youtube"
    private val ig = "com.instagram.android"

    @Before
    fun setUp() {
        guard = ShortFormGuard()
        guard.setAllowance(1)
    }

    @Test
    fun firstShortIsAllowedAndBudgetIsSpent() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertEquals(0, guard.remainingFor(yt))
    }

    @Test
    fun stayingInTheSameVisitNeverBlocksRepeatedly() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertTrue(guard.onPlayerVisible(yt, 1_400))
        assertTrue(guard.onPlayerVisible(yt, 1_900))
    }

    @Test
    fun scrollingToTheNextShortBlocks() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        // After the entry grace window the swipe to the next short is a block.
        assertTrue(guard.onPlayerScroll(yt, 3_000))
    }

    @Test
    fun scrollDuringEntryGraceIsIgnored() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertFalse(guard.onPlayerScroll(yt, 1_400)) // pager settling
    }

    @Test
    fun scrollOutsideThePlayerIsIgnored() {
        assertFalse(guard.onPlayerScroll(yt, 10_000))
    }

    @Test
    fun reopeningAfterTheBudgetIsSpentBlocksImmediately() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertTrue(guard.onPlayerScroll(yt, 5_000))
        // User is forced out, waits, taps Shorts again 10s later.
        assertFalse(guard.onPlayerVisible(yt, 15_000))
    }

    @Test
    fun blockIsDebouncedSoTheOverlayCanSettle() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertTrue(guard.onPlayerScroll(yt, 5_000))
        // Still detected on screen 1s later: do not back-press again.
        assertTrue(guard.onPlayerVisible(yt, 6_000))
    }

    @Test
    fun zeroAllowanceBlocksOnFirstView() {
        guard.setAllowance(0)
        assertFalse(guard.onPlayerVisible(yt, 1_000))
    }

    @Test
    fun budgetIsPerApp() {
        guard.setAllowance(1)
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertTrue(guard.onPlayerVisible(ig, 1_500))
        assertEquals(0, guard.remainingFor(yt))
        assertEquals(0, guard.remainingFor(ig))
        assertFalse(guard.onPlayerVisible(ig, 20_000))
    }

    @Test
    fun resetGivesAFreshBudgetForTheNextSession() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        assertTrue(guard.onPlayerScroll(yt, 5_000))
        assertFalse(guard.onPlayerVisible(yt, 10_000))
        guard.reset()
        assertTrue(guard.onPlayerVisible(yt, 60_000))
        assertEquals(0, guard.remainingFor(yt))
    }

    @Test
    fun allowanceIsClampedAndNotifiesVisits() {
        guard.setAllowance(99)
        assertEquals(ShortFormGuard.MAX_ALLOWANCE, guard.currentAllowance())
        guard.setAllowance(-3)
        assertEquals(0, guard.currentAllowance())
    }

    @Test
    fun playerGoneEndsTheVisitSoTheNextSightingIsJudgedAgain() {
        assertTrue(guard.onPlayerVisible(yt, 1_000))
        guard.onPlayerGone(yt)
        // Same visit window but the player left the screen: budget already spent.
        assertFalse(guard.onPlayerVisible(yt, 2_000))
    }
}

package com.jainam.studentos.focusshield.feature.blocking

import com.jainam.studentos.focusshield.data.CategoryType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decision-path regression tests for the "Deep Lock blocks wrongly" report.
 *
 * Before: every installed app was persisted as BLOCKED by the scanner and the
 * engine used whitelist semantics, so any session blocked all of them.
 */
class BlockingRulesTest {

    @Test
    fun blocklistModeAllowsAppsTheUserNeverPicked() {
        // The regression: these used to return true (blocked).
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.UNCATEGORIZED))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, null))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.USER_ALLOWED))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.STUDY))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.SOCIAL_MEDIA))
    }

    @Test
    fun blocklistModeBlocksOnlyExplicitPicks() {
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.BLOCKED))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.ALWAYS_BLOCKED))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.BREAK_BLOCKED))
    }

    @Test
    fun strictModeIsWhitelistAndHonoursTheAllowlist() {
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STRICT, CategoryType.UNCATEGORIZED))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STRICT, null))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STRICT, CategoryType.BLOCKED))
        // Strict must respect the user's allowlist (it used to ignore it).
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STRICT, CategoryType.USER_ALLOWED))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STRICT, CategoryType.STUDY))
    }

    @Test
    fun essentialAppsAreNeverBlockedInAnyMode() {
        for (mode in BlockingMode.values()) {
            assertFalse(
                "essential blocked in $mode",
                BlockingRules.shouldBlock(mode, CategoryType.ESSENTIAL)
            )
        }
    }

    @Test
    fun breakModeAllowsEverythingExceptExplicitLists() {
        assertFalse(BlockingRules.shouldBlock(BlockingMode.BREAK, CategoryType.BLOCKED))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.BREAK, null))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.BREAK, CategoryType.ALWAYS_BLOCKED))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.BREAK, CategoryType.BREAK_BLOCKED))
    }

    /**
     * Regression: "Deep Lock does nothing on a fresh install". An empty
     * allowlist used to disable the engine in every mode; only whitelist
     * (STRICT) mode may require one.
     */
    @Test
    fun onlyStrictModeRequiresAnAllowlist() {
        assertTrue(BlockingRules.requiresAllowlist(BlockingMode.STRICT))
        assertFalse(BlockingRules.requiresAllowlist(BlockingMode.STANDARD))
        assertFalse(BlockingRules.requiresAllowlist(BlockingMode.BREAK))
    }

    @Test
    fun blocklistModeBlocksExplicitPicksEvenWhenTheAllowlistIsEmpty() {
        // STANDARD + empty allowlist + user-picked app → must still block.
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.ALWAYS_BLOCKED))
        assertTrue(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.BLOCKED))
        // ...and everything the user never picked stays allowed.
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, null))
        assertFalse(BlockingRules.shouldBlock(BlockingMode.STANDARD, CategoryType.UNCATEGORIZED))
    }
}

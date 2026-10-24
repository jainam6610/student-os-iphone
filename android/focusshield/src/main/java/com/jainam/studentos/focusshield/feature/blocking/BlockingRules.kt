package com.jainam.studentos.focusshield.feature.blocking

import com.jainam.studentos.focusshield.data.CategoryType

/**
 * Pure blocking decision matrix (no Android dependencies, unit-tested).
 *
 * Default is BLOCKLIST: only apps the user explicitly picked are blocked.
 * Everything else — including apps the scanner has not classified — is allowed.
 * Whitelist semantics live behind the opt-in STRICT mode ("Strict" toggle).
 */
object BlockingRules {

    /** Packages allowed in whitelist/STRICT mode. */
    val STRICT_ALLOWED: Set<CategoryType> = setOf(
        CategoryType.ESSENTIAL,
        CategoryType.STUDY,
        CategoryType.USER_ALLOWED
    )

    /**
     * Only whitelist mode needs a non-empty allowlist to be safe. In BLOCKLIST
     * (STANDARD) and BREAK modes the allowlist is irrelevant: an empty one must
     * never disable blocking, that was the "Deep Lock does nothing on a fresh
     * install" bug.
     */
    fun requiresAllowlist(mode: BlockingMode): Boolean = mode == BlockingMode.STRICT

    /**
     * @param category row from `app_categories`, or null when the package has no
     *   row at all (e.g. freshly installed app that was never scanned).
     */
    fun shouldBlock(mode: BlockingMode, category: CategoryType?): Boolean {
        // Explicit user-blocked lists win in every mode, breaks included.
        if (category == CategoryType.ALWAYS_BLOCKED || category == CategoryType.BREAK_BLOCKED) {
            return true
        }
        if (mode == BlockingMode.BREAK) {
            return false
        }
        if (category == null) {
            // Blocklist: never picked → allow. Whitelist: unknown → block.
            return mode == BlockingMode.STRICT
        }
        return when (mode) {
            BlockingMode.STANDARD -> category == CategoryType.BLOCKED
            BlockingMode.STRICT -> category !in STRICT_ALLOWED
            BlockingMode.BREAK -> false
        }
    }
}

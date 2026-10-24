package com.jainam.studentos.focusshield.feature.blocking

enum class BlockingMode {
    /**
     * Blocklist mode — the default. Only apps the user explicitly picked
     * (BLOCKED / ALWAYS_BLOCKED / BREAK_BLOCKED) are blocked; every other app,
     * including apps the scanner never classified, is allowed.
     */
    STANDARD,
    /**
     * Whitelist mode ("Strict"). Everything except ESSENTIAL/STUDY/USER_ALLOWED
     * is blocked. Opt-in only.
     */
    STRICT,
    /**
     * Break time: only apps on the ALWAYS_BLOCKED / BREAK_BLOCKED lists are
     * blocked. Everything else (including unknown packages) is allowed, so a
     * break is actually a break. Arming a break session as STANDARD used to
     * block every non-whitelisted app during the break.
     */
    BREAK
}

package com.jainam.studentos.focusshield.feature.blocking

/**
 * Session-scoped budget for short-form video ("Regain study mode" behaviour).
 *
 * The user gets N shorts/reels per focus session. The first one plays normally;
 * the moment the player scrolls to the next one — or the player is opened again
 * after the budget is spent — the guard reports a block and the caller forces
 * the user out and shows the block screen.
 *
 * All state is per focus session: [reset] is called on session start/end, and
 * since the budget is consumed while the player stays open, re-opening Shorts
 * after the first one is refused immediately.
 *
 * Not thread-safe by design: it is only driven from the accessibility event
 * thread. The internal map is concurrent so a reset from another thread cannot
 * corrupt it mid-read.
 */
class ShortFormGuard {

    private class VisitState {
        var remaining: Int = 0
        /** True while the user is inside one continuous player visit. */
        var inVisit: Boolean = false
        var visitStartedAt: Long = 0L
        var lastSeenAt: Long = 0L
        var lastBlockAt: Long = 0L

        fun wasBlockedRecently(now: Long): Boolean =
            lastBlockAt != 0L && now - lastBlockAt < BLOCK_DEBOUNCE_MS
    }

    @Volatile
    private var allowance: Int = DEFAULT_ALLOWANCE

    private val states = java.util.concurrent.ConcurrentHashMap<String, VisitState>()

    /** Re-arms the budget. Clears every visit, so the next view consumes it. */
    fun setAllowance(value: Int) {
        allowance = value.coerceIn(0, MAX_ALLOWANCE)
        states.clear()
    }

    fun currentAllowance(): Int = allowance

    /** Start of a new focus session (or its end): every app gets a fresh budget. */
    fun reset() {
        states.clear()
    }

    /**
     * The short-form player is on screen.
     *
     * @return true when this view is allowed (budget consumed, or the same visit
     *         still running), false when the caller must block and force the
     *         user out of the player.
     */
    fun onPlayerVisible(packageName: String, now: Long): Boolean {
        val state = states.getOrPut(packageName) { VisitState().also { it.remaining = allowance } }
        val previousSeen = state.lastSeenAt
        state.lastSeenAt = now

        // The player was gone long enough that this is a new visit.
        if (state.inVisit && now - previousSeen > VISIT_GAP_MS) {
            state.inVisit = false
        }

        // A block was just triggered: stay quiet while the overlay settles so the
        // accessibility thread does not fire back-press + overlay in a loop.
        if (state.wasBlockedRecently(now)) return true

        if (state.inVisit) return true

        state.inVisit = true
        state.visitStartedAt = now

        if (state.remaining > 0) {
            state.remaining -= 1
            return true
        }

        // Budget spent: refuse this visit outright.
        state.inVisit = false
        state.lastBlockAt = now
        return false
    }

    /**
     * A scroll was observed inside the short-form player.
     *
     * @return true when the caller must block (the user is scrolling to the next
     *         short/reel), false when the scroll is outside the player, lands in
     *         the entry grace window, or a block just fired.
     */
    fun onPlayerScroll(packageName: String, now: Long): Boolean {
        val state = states[packageName] ?: return false
        if (!state.inVisit) return false
        // Ignore the settle-scroll right after the player opens.
        if (now - state.visitStartedAt < ENTRY_GRACE_MS) return false
        if (state.wasBlockedRecently(now)) return false

        state.lastBlockAt = now
        state.inVisit = false
        return true
    }

    /** The player is no longer on screen; the next sighting is a new visit. */
    fun onPlayerGone(packageName: String) {
        states[packageName]?.inVisit = false
    }

    /**
     * Give [packageName] a fresh budget without clearing other packages.
     *
     * Used outside a focus session: the budget is "per app-open" there, so
     * re-opening the app tomorrow must allow its one short again (unlike a
     * session, where the budget deliberately survives until it ends).
     */
    fun rearm(packageName: String) {
        val state = states[packageName] ?: return
        state.remaining = allowance
        state.inVisit = false
        state.lastBlockAt = 0L
        state.visitStartedAt = 0L
    }

    fun remainingFor(packageName: String): Int = states[packageName]?.remaining ?: allowance

    companion object {
        const val DEFAULT_ALLOWANCE = 1
        const val MAX_ALLOWANCE = 10
    }
}

/** Gap after which the player counts as re-opened. */
private const val VISIT_GAP_MS = 4_000L
/** Ignore scrolls this soon after the player appears (pager settling). */
private const val ENTRY_GRACE_MS = 1_200L
/** Do not re-block while the previous block overlay is settling. */
private const val BLOCK_DEBOUNCE_MS = 3_000L

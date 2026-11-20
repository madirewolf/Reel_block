package com.reelblock.app

/**
 * Timing rules for the full-screen block cover, kept free of Android
 * types so they are unit-tested.
 *
 * The cover goes up the instant a block fires so the reel is never
 * visible while the service navigates away. It comes down as soon as
 * the user has landed somewhere that is not a reel (but never sooner
 * than [minVisibleMs], so it does not flicker), and in any case after
 * [maxVisibleMs] so a failed navigation can never leave Instagram
 * hidden behind it.
 */
class CoverTiming(
    val minVisibleMs: Long = 250L,
    val maxVisibleMs: Long = 1200L,
) {
    private var shownAt: Long = NOT_SHOWN

    val isShown: Boolean get() = shownAt != NOT_SHOWN

    /** Cover shown (or re-shown by a repeat block) at [now]. Returns the
     *  delay after which it must auto-hide. */
    fun onShow(now: Long): Long {
        shownAt = now
        return maxVisibleMs
    }

    /** The user has left the blocked screen. Returns how long to wait
     *  before hiding, or null if the cover is not up. */
    fun delayBeforeHideOnLanding(now: Long): Long? {
        if (!isShown) return null
        return (minVisibleMs - (now - shownAt)).coerceAtLeast(0L)
    }

    fun onHidden() {
        shownAt = NOT_SHOWN
    }

    private companion object {
        const val NOT_SHOWN = Long.MIN_VALUE
    }
}

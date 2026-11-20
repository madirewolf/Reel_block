package com.reelblock.app

/**
 * When the block cover goes up early, and how long Home-feed covers
 * outlast a block. Kept off the service so the rules run on the JVM.
 */
internal object CoverPolicy {
    /** A tap this recent is the user's own, not a late or replayed event. */
    const val FRESH_TAP_MS = 300L

    /**
     * After a block, Home-feed covers stay up this long even if a stale
     * tree says the user is not on Home. Without it the bounce from the
     * Reels tab back to Home showed the feed's reels uncovered for
     * about half a second.
     */
    const val FEED_HOLD_MS = 1500L

    /**
     * A real tap on the bottom-nav Reels tab: a click or select event
     * whose source id is the Reels tab, delivered within [FRESH_TAP_MS].
     * Instagram 457 sends the select first, ~200 ms before the click.
     * A select event fires only when the tab becomes selected, so it is
     * as good as the click. This is enough to block at once: the view
     * tree at that moment still shows the old tab selected, and waiting
     * for it only lets Reels start playing.
     */
    fun isFreshReelsTabTap(isTapEvent: Boolean, sourceViewId: String?, eventAgeMs: Long): Boolean =
        isTapEvent &&
            ReelDetector.tabLabelFromViewId(sourceViewId) == ReelDetector.TAB_REELS &&
            eventAgeMs in 0..FRESH_TAP_MS

    /**
     * A tree read that began before our last block action shows a screen
     * the user has already been moved off. Acting on it undid the block
     * (a pre-tap Home tree reset the session) and could tap Home again.
     */
    fun isTreeReadStale(readStartedAt: Long, lastBlockActionAt: Long): Boolean =
        lastBlockActionAt > 0 && lastBlockActionAt >= readStartedAt

    /**
     * A cover put up early on a scroll that looked like a swipe off an
     * allowed DM reel comes down when a tree read started after it does
     * not end in a block. A read that started before the cover still
     * shows the screen from before the swipe, so it does not count.
     */
    fun dropEarlyCover(coverShownAt: Long, readStartedAt: Long, blocking: Boolean): Boolean =
        coverShownAt > 0 && readStartedAt >= coverShownAt && !blocking

    /**
     * Whether the user is on the Home feed after a tree read.
     *
     * A scroll in the feed is read from the scrolled list alone, which has
     * no tab bar in it. Treating "no tab seen" as "not on Home" turned the
     * feed covers off for ~2 s on every scroll. So only a reel screen, a
     * different selected tab, or a whole-window read with no tab bar (a
     * full-screen page over Home) moves the user off Home; anything else
     * keeps the last answer.
     */
    fun onHomeFeedAfterRead(
        previous: Boolean,
        screen: ScreenType,
        selectedTab: String?,
        wholeWindow: Boolean,
        tabBarSeen: Boolean,
    ): Boolean = when {
        screen != ScreenType.OTHER -> false
        selectedTab == ReelDetector.TAB_HOME -> true
        !selectedTab.isNullOrBlank() -> false
        wholeWindow && !tabBarSeen -> false
        else -> previous
    }
}

/** Keeps Home-feed covers up through a block; see [CoverPolicy.FEED_HOLD_MS]. */
internal class FeedCoverHold(private val holdMs: Long = CoverPolicy.FEED_HOLD_MS) {
    private var until = 0L

    fun start(now: Long) {
        until = now + holdMs
    }

    fun isHeld(now: Long): Boolean = now < until

    fun release() {
        until = 0L
    }
}

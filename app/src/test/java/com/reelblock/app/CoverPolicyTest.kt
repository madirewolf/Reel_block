package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bug: on the phone a Reels-tab tap showed Reels for ~0.1 s, then the
 * Home feed with its reels uncovered for ~0.5 s, and only then the block
 * cover. The service waited for a view tree that still showed Home
 * selected, and dropped the feed covers as soon as it saw the Reels tab.
 */
class CoverPolicyTest {

    private val ig = "com.instagram.android:id/"

    @Test
    fun `a fresh click on the Reels tab blocks at once`() {
        assertThat(CoverPolicy.isFreshReelsTabTap(true, ig + "clips_tab", eventAgeMs = 20)).isTrue()
    }

    @Test
    fun `a late click is left to the tree`() {
        // Seen on the phone: a click delivered 885 ms late re-fired the
        // Home tap after the user was already on Home.
        assertThat(CoverPolicy.isFreshReelsTabTap(true, ig + "clips_tab", eventAgeMs = 885)).isFalse()
    }

    @Test
    fun `the select event that comes before the click counts too`() {
        // Instagram 457 sends the select ~200 ms before the click.
        assertThat(CoverPolicy.isFreshReelsTabTap(isTapEvent = true, ig + "clips_tab", 90)).isTrue()
    }

    @Test
    fun `only a tap on the bottom-nav Reels tab counts`() {
        // A content change or scroll on the tab is not a tap.
        assertThat(CoverPolicy.isFreshReelsTabTap(false, ig + "clips_tab", 20)).isFalse()
        // Another user's profile has a "Reels" grid tab with its own id.
        assertThat(CoverPolicy.isFreshReelsTabTap(true, ig + "profile_tab_reels", 20)).isFalse()
        assertThat(CoverPolicy.isFreshReelsTabTap(true, ig + "feed_tab", 20)).isFalse()
        assertThat(CoverPolicy.isFreshReelsTabTap(true, null, 20)).isFalse()
    }

    @Test
    fun `a clock skew never counts as fresh`() {
        assertThat(CoverPolicy.isFreshReelsTabTap(true, ig + "clips_tab", eventAgeMs = -5)).isFalse()
    }

    @Test
    fun `a tree read from before our block is dropped`() {
        // Read started at 1000, the Reels tap was blocked at 1100: the
        // read still shows Home from before the tap.
        assertThat(CoverPolicy.isTreeReadStale(readStartedAt = 1_000, lastBlockActionAt = 1_100)).isTrue()
        assertThat(CoverPolicy.isTreeReadStale(readStartedAt = 1_000, lastBlockActionAt = 1_000)).isTrue()
    }

    @Test
    fun `a tree read from after our block is used`() {
        assertThat(CoverPolicy.isTreeReadStale(readStartedAt = 1_200, lastBlockActionAt = 1_100)).isFalse()
        assertThat(CoverPolicy.isTreeReadStale(readStartedAt = 1_200, lastBlockActionAt = 0)).isFalse()
    }

    @Test
    fun `feed covers are held through the bounce and then let go`() {
        val hold = FeedCoverHold(holdMs = 1500)
        assertThat(hold.isHeld(now = 0)).isFalse()
        hold.start(now = 10_000)
        assertThat(hold.isHeld(now = 10_000)).isTrue()
        assertThat(hold.isHeld(now = 11_499)).isTrue()
        assertThat(hold.isHeld(now = 11_500)).isFalse()
    }

    @Test
    fun `a feed scroll read from the list alone keeps the user on Home`() {
        // Bug: every scroll read only the RecyclerView, saw no tab bar,
        // and turned the feed covers off for ~2 s.
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(
                previous = true, screen = ScreenType.OTHER, selectedTab = null,
                wholeWindow = false, tabBarSeen = false,
            ),
        ).isTrue()
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(
                previous = false, screen = ScreenType.OTHER, selectedTab = null,
                wholeWindow = false, tabBarSeen = false,
            ),
        ).isFalse()
    }

    @Test
    fun `home selected means on Home`() {
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(false, ScreenType.OTHER, ReelDetector.TAB_HOME, true, true),
        ).isTrue()
    }

    @Test
    fun `another tab, a reel screen, or a full-screen page means off Home`() {
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(true, ScreenType.OTHER, ReelDetector.TAB_SEARCH, true, true),
        ).isFalse()
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(true, ScreenType.REEL_VIEWER, null, false, false),
        ).isFalse()
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(true, ScreenType.REELS_TAB, ReelDetector.TAB_REELS, true, true),
        ).isFalse()
        // Whole window, no tab bar: a DM thread or post opened over Home.
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(true, ScreenType.OTHER, null, wholeWindow = true, tabBarSeen = false),
        ).isFalse()
    }

    @Test
    fun `tab bar seen but its selection not filled in yet keeps the last answer`() {
        // Samsung sometimes leaves the selected state empty for a moment.
        assertThat(
            CoverPolicy.onHomeFeedAfterRead(true, ScreenType.OTHER, null, wholeWindow = true, tabBarSeen = true),
        ).isTrue()
    }

    @Test
    fun `an early DM-swipe cover stays while the swipe is blocked`() {
        assertThat(CoverPolicy.dropEarlyCover(coverShownAt = 1_000, readStartedAt = 1_010, blocking = true)).isFalse()
    }

    @Test
    fun `an early DM-swipe cover comes down if the user is still on the allowed reel`() {
        assertThat(CoverPolicy.dropEarlyCover(coverShownAt = 1_000, readStartedAt = 1_010, blocking = false)).isTrue()
        assertThat(CoverPolicy.dropEarlyCover(coverShownAt = 1_000, readStartedAt = 1_000, blocking = false)).isTrue()
    }

    @Test
    fun `a read from before the early cover cannot take it down`() {
        assertThat(CoverPolicy.dropEarlyCover(coverShownAt = 1_000, readStartedAt = 990, blocking = false)).isFalse()
        assertThat(CoverPolicy.dropEarlyCover(coverShownAt = 0, readStartedAt = 990, blocking = false)).isFalse()
    }

    @Test
    fun `releasing the hold ends it at once`() {
        val hold = FeedCoverHold(holdMs = 1500)
        hold.start(now = 10_000)
        hold.release()
        assertThat(hold.isHeld(now = 10_001)).isFalse()
    }
}

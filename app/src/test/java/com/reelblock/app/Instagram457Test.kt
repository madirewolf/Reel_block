package com.reelblock.app

import android.view.accessibility.AccessibilityEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Instagram 457 (Nov 2026) changed the bottom bar and keeps every tab's
 * page mounted. Each test here is a bug the old detector had on a live
 * Galaxy S24 tree.
 */
class Instagram457Test {

    private val IG = "com.instagram.android:id/"

    @Test
    fun `tab_bar is recognised as the bottom bar`() {
        // Bug: only `main_tab_bar` was known, so the Reels tab was never
        // seen as selected and the block stalled.
        assertThat(ReelDetector.isTabBarId(IG + "tab_bar")).isTrue()
        assertThat(ReelDetector.isTabBarId(IG + "main_tab_bar")).isTrue()
        assertThat(ReelDetector.isTabBarId(IG + "tab_bar_shadow")).isFalse()
    }

    @Test
    fun `reels tab selected in the 457 bar is REELS_TAB`() {
        val screen = NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_REELS)
        assertThat(ReelDetector.detectScreen(screen)).isEqualTo(ScreenType.REELS_TAB)
        assertThat(ReelDetector.extractSelectedBottomTab(screen)).isEqualTo(ReelDetector.TAB_REELS)
    }

    @Test
    fun `home with the reels page mounted but hidden is OTHER`() {
        // Bug: hidden pager pages were read, so Home looked like a reel
        // viewer and the Reels page header looked like a selected tab.
        val screen = NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_HOME)
        assertThat(ReelDetector.detectScreen(screen)).isEqualTo(ScreenType.OTHER)
        assertThat(ReelDetector.extractSelectedBottomTab(screen)).isEqualTo(ReelDetector.TAB_HOME)
    }

    @Test
    fun `hidden reels page never yields a reel signature`() {
        val screen = NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_HOME)
        assertThat(ReelDetector.extractReelSignature(screen)).isNull()
    }

    @Test
    fun `message tab selected is DM_INBOX`() {
        // Bug: Messages became a bottom tab and read as OTHER, which wiped
        // the "came from DMs" context.
        val screen = NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_DIRECT)
        assertThat(ReelDetector.detectScreen(screen)).isEqualTo(ScreenType.DM_INBOX)
    }

    @Test
    fun `tab ids map both ways`() {
        assertThat(ReelDetector.tabLabelFromViewId(IG + "feed_tab")).isEqualTo(ReelDetector.TAB_HOME)
        assertThat(ReelDetector.tabLabelFromViewId(IG + "clips_tab")).isEqualTo(ReelDetector.TAB_REELS)
        assertThat(ReelDetector.tabLabelFromViewId(IG + "direct_tab")).isEqualTo(ReelDetector.TAB_DIRECT)
        assertThat(ReelDetector.tabViewIdSegment(ReelDetector.TAB_HOME)).isEqualTo("feed_tab")
        assertThat(ReelDetector.tabViewIdSegment(ReelDetector.TAB_SEARCH)).isEqualTo("search_tab")
        assertThat(ReelDetector.tabViewIdSegment("nope")).isNull()
    }

    @Test
    fun `message tab description maps to direct`() {
        assertThat(ReelDetector.tabLabelFromDescription("Message")).isEqualTo(ReelDetector.TAB_DIRECT)
        assertThat(ReelDetector.tabLabelFromDescription("Search and explore")).isEqualTo(ReelDetector.TAB_SEARCH)
    }

    @Test
    fun `tap on the bottom reels tab id is REELS_TAB`() {
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED, "Reels", IG + "clips_tab",
        )
        assertThat(type).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `tap on the message tab is DM_INBOX`() {
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED, "Message", IG + "direct_tab",
        )
        assertThat(type).isEqualTo(ScreenType.DM_INBOX)
    }

    @Test
    fun `tap on a profile's Reels grid tab is not the Reels tab`() {
        // Bug: any click described "Reels" counted as the Reels tab, so
        // opening the Reels grid on someone's profile was blocked.
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED, "Reels", IG + "profile_tab_icon_view",
        )
        assertThat(type).isNull()
    }

    @Test
    fun `tap with no view id still falls back to the description`() {
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_VIEW_CLICKED, "Reels", null,
        )
        assertThat(type).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `a long-press preview playing a reel on Explore is a reel`() {
        // Bug: holding a reel tile on Explore played it in a preview. The
        // Search tab stays selected under it, so it read as OTHER.
        val screen = NodeSnapshotFactory.ig457ExplorePeek(video = true)
        assertThat(ReelDetector.detectScreen(screen)).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `a long-press preview of a photo is left alone`() {
        val screen = NodeSnapshotFactory.ig457ExplorePeek(video = false)
        assertThat(ReelDetector.detectScreen(screen)).isEqualTo(ScreenType.OTHER)
    }

    @Test
    fun `the preview read on its own is a reel too`() {
        // With the menu focused, the service reads just the preview node.
        val peek = NodeSnapshotFactory.ig457ExplorePeek(video = true).flatten()
            .first { it.viewIdResourceName == IG + "peek_container" }
        assertThat(ReelDetector.detectScreen(peek)).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `the preview's menu window points at the preview behind it`() {
        assertThat(ReelDetector.isPeekMenu(NodeSnapshotFactory.ig457PeekMenu())).isTrue()
        assertThat(ReelDetector.detectScreen(NodeSnapshotFactory.ig457PeekMenu())).isEqualTo(ScreenType.OTHER)
        assertThat(ReelDetector.isPeekMenu(NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_SEARCH))).isFalse()
        assertThat(ReelDetector.isPeekMenu(NodeSnapshotFactory.ig457ExplorePeek(video = true))).isFalse()
        assertThat(ReelDetector.isPeekMenu(null)).isFalse()
    }

    @Test
    fun `browsing Explore then holding a reel tile blocks it`() {
        val sm = SessionStateMachine()
        sm.handleScreen(ReelDetector.detectScreen(NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_SEARCH)), null)
        val peek = NodeSnapshotFactory.ig457ExplorePeek(video = true)
        val action = sm.handleScreen(ReelDetector.detectScreen(peek), ReelDetector.extractReelSignature(peek))
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `non click events are not tab taps`() {
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "Reels", IG + "clips_tab",
        )
        assertThat(type).isNull()
    }
}

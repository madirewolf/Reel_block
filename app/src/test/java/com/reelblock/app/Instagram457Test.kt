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
    fun `selected state on the tab icon alone still names the tab`() {
        // Bug: the Nov 20 2026 Instagram update marks only the button's
        // unlabelled tab_icon child selected, so no tab was found and the
        // Home feed lost its reel covers.
        fun bar(selected: String) = NodeSnapshotFactory.node(
            id = IG + "tab_bar",
            children = listOf("feed_tab" to "Home", "clips_tab" to "Reels", "search_tab" to "Search and explore")
                .map { (id, desc) ->
                    NodeSnapshotFactory.node(
                        id = IG + id,
                        contentDescription = desc,
                        children = listOf(
                            NodeSnapshotFactory.node(
                                id = IG + "tab_icon",
                                isSelected = ReelDetector.tabLabelFromViewId(IG + id) == selected,
                            ),
                        ),
                    )
                },
        )
        fun screen(selected: String) = NodeSnapshotFactory.node(children = listOf(bar(selected)))
        assertThat(ReelDetector.extractSelectedBottomTab(screen(ReelDetector.TAB_HOME)))
            .isEqualTo(ReelDetector.TAB_HOME)
        assertThat(ReelDetector.extractSelectedBottomTab(screen(ReelDetector.TAB_REELS)))
            .isEqualTo(ReelDetector.TAB_REELS)
        assertThat(ReelDetector.detectScreen(screen(ReelDetector.TAB_REELS)))
            .isEqualTo(ScreenType.REELS_TAB)
        // An unrelated selected node outside any tab names nothing.
        val stray = NodeSnapshotFactory.node(children = listOf(
            NodeSnapshotFactory.node(id = IG + "tab_bar", children = listOf(
                NodeSnapshotFactory.node(id = IG + "tab_icon", isSelected = true),
            )),
        ))
        assertThat(ReelDetector.extractSelectedBottomTab(stray)).isNull()
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
    fun `opening a Home feed reel is not a Reels tab tap`() {
        // Bug: Instagram 457 opens a feed reel inside the Reels tab and
        // fires a select event from an unnamed "Reels" view. Read as a
        // tab tap, it blocked the reel with Home switched off.
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_VIEW_SELECTED, "Reels", null,
        )
        assertThat(type).isNull()
        assertThat(ReelDetector.bottomNavTabForEvent("Reels", null, isClick = false)).isNull()
        // A real tap on the tab names its button, selected or clicked.
        assertThat(
            ReelDetector.classifyByBottomNavEvent(AccessibilityEvent.TYPE_VIEW_SELECTED, "Reels", IG + "clips_tab"),
        ).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `a feed reel's full screen player with a Reels header is a reel viewer`() {
        // Bug: the player Instagram 457 opens from the Home feed has no tab
        // bar and a selected "Reels" header, which read as the Reels tab.
        val player = NodeSnapshotFactory.node(children = listOf(
            NodeSnapshotFactory.node(contentDescription = "Reels", isSelected = true, className = "android.widget.LinearLayout"),
            NodeSnapshotFactory.node(id = IG + "clips_viewer_view_pager"),
        ))
        assertThat(ReelDetector.extractSelectedBottomTab(player)).isNull()
        assertThat(ReelDetector.detectScreen(player)).isEqualTo(ScreenType.REEL_VIEWER)
        // The tab's own id still counts without a bar around it.
        val tab = NodeSnapshotFactory.node(children = listOf(
            NodeSnapshotFactory.node(id = IG + "clips_tab", contentDescription = "Reels", isSelected = true),
        ))
        assertThat(ReelDetector.extractSelectedBottomTab(tab)).isEqualTo(ReelDetector.TAB_REELS)
    }

    @Test
    fun `a Home feed reel follows the Home switch`() {
        val sm = SessionStateMachine().apply { blockHomeReels = false }
        sm.handleScreen(ScreenType.OTHER, null)
        assertThat(sm.handleScreen(ScreenType.REEL_VIEWER, "user:some.author", SessionStateMachine.ReelOrigin.HOME))
            .isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        val on = SessionStateMachine()
        assertThat(on.handleScreen(ScreenType.REEL_VIEWER, "user:some.author", SessionStateMachine.ReelOrigin.HOME))
            .isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `non click events are not tab taps`() {
        val type = ReelDetector.classifyByBottomNavEvent(
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, "Reels", IG + "clips_tab",
        )
        assertThat(type).isNull()
    }
}

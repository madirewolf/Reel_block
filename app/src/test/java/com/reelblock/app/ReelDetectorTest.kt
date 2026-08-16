package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReelDetectorTest {

    @Test
    fun `null snapshot is UNKNOWN`() {
        assertThat(ReelDetector.detectScreen(null)).isEqualTo(ScreenType.UNKNOWN)
    }

    @Test
    fun `reels tab with bottom nav selected is REELS_TAB`() {
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.reelsTabScreen())
        assertThat(screen).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `reel viewer without bottom nav is REEL_VIEWER`() {
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(screen).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `dm thread is DM_THREAD`() {
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.dmThreadScreen())
        assertThat(screen).isEqualTo(ScreenType.DM_THREAD)
    }

    @Test
    fun `dm inbox is DM_INBOX`() {
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.dmInboxScreen())
        assertThat(screen).isEqualTo(ScreenType.DM_INBOX)
    }

    @Test
    fun `home feed is OTHER`() {
        // The fixture includes the action bar's inbox button (id matches
        // "inbox_") — a Galaxy S24 log showed the home feed classified
        // DM_INBOX because of it. A selected non-Reels tab must win.
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.homeFeedScreen())
        assertThat(screen).isEqualTo(ScreenType.OTHER)
    }

    @Test
    fun `home feed with no tab selected is OTHER not REEL_VIEWER`() {
        // Samsung leaves every bottom-nav tab unselected for 300-700 ms
        // after a tab switch. The home feed carries 3 action
        // descriptions + a video SurfaceView, which used to fall through
        // to the fullscreen reel-player heuristics and false-block.
        val screen = ReelDetector.detectScreen(
            NodeSnapshotFactory.homeFeedScreen(homeTabSelected = false),
        )
        assertThat(screen).isEqualTo(ScreenType.OTHER)
    }

    @Test
    fun `reels tab loading with no tab selected is REELS_TAB`() {
        // Same unselected-tab window, but the clips viewer is already
        // mounting under the tab bar — that's the Reels tab loading.
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(
                    id = "com.instagram.android:id/clips_viewer_view_pager",
                    isScrollable = true,
                ),
                NodeSnapshotFactory.node(
                    id = "com.instagram.android:id/main_tab_bar",
                    children = listOf(
                        NodeSnapshotFactory.node(contentDescription = "Home", isSelected = false),
                        NodeSnapshotFactory.node(contentDescription = "Reels", isSelected = false),
                    ),
                ),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `loading screen is OTHER`() {
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.loadingScreen())
        assertThat(screen).isEqualTo(ScreenType.OTHER)
    }

    // ---- Stories (reel_* ids) must never classify as DM or reel ----

    @Test
    fun `fullscreen story viewer is OTHER not DM_THREAD or REEL_VIEWER`() {
        // Field bug: the story reply box ("Send message" EditText)
        // matched the DM-composer fallback, planting fake "came from
        // DM" context — watch a story, open any reel, and it was
        // allowed. The like/share icons + SurfaceView could also trip
        // the fuzzy reel-player heuristics.
        val screen = ReelDetector.detectScreen(NodeSnapshotFactory.storyViewerScreen())
        assertThat(screen).isEqualTo(ScreenType.OTHER)
    }

    @Test
    fun `story viewer with tab bar in tree is OTHER`() {
        val screen = ReelDetector.detectScreen(
            NodeSnapshotFactory.storyViewerScreen(withTabBar = true),
        )
        assertThat(screen).isEqualTo(ScreenType.OTHER)
    }

    @Test
    fun `real reel viewer still wins over story-like ids`() {
        // clips_* ids are authoritative — a tree with BOTH a clips
        // viewer and residual story ids is the reel player.
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(id = "com.instagram.android:id/clips_viewer_view_pager"),
                NodeSnapshotFactory.node(id = "com.instagram.android:id/reel_sticker_overlay_container"),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REEL_VIEWER)
    }

    // ---- Fallback coverage: detect without "clips" ids ----

    @Test
    fun `reel viewer detected from action icons and video surface alone`() {
        // Simulates an Instagram release that renames the clips_* ids —
        // we should still detect the reel viewer from 3+ action icons
        // plus a video surface.
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(className = "android.view.SurfaceView"),
                NodeSnapshotFactory.node(contentDescription = "Like"),
                NodeSnapshotFactory.node(contentDescription = "Comments"),
                NodeSnapshotFactory.node(contentDescription = "Share"),
                NodeSnapshotFactory.node(contentDescription = "Audio"),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `reel viewer detected from many action icons without a surface`() {
        // Some releases expose the video as a different class — require
        // 4+ action icons in that case to avoid false positives on
        // regular posts.
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(contentDescription = "Like"),
                NodeSnapshotFactory.node(contentDescription = "Comments"),
                NodeSnapshotFactory.node(contentDescription = "Share"),
                NodeSnapshotFactory.node(contentDescription = "Save"),
                NodeSnapshotFactory.node(contentDescription = "Original audio"),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `selected clips_tab node alone is enough for REELS_TAB`() {
        // Mirrors TYPE_VIEW_SELECTED firing on the Reels tab — the
        // event.source subtree may be only this one node.
        val root = NodeSnapshotFactory.node(
            id = "com.instagram.android:id/clips_tab",
            isSelected = true,
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `reels tab wins even with no clips id visible during transition`() {
        // Just after tapping the Reels tab, the pager may not be in
        // the tree yet — but the bottom-nav tab still reports as
        // "selected". We should already block.
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(id = "com.instagram.android:id/main_tab_bar", children = listOf(
                    NodeSnapshotFactory.node(contentDescription = "Home", isSelected = false),
                    NodeSnapshotFactory.node(contentDescription = "Reels", isSelected = true),
                )),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.REELS_TAB)
    }

    @Test
    fun `dm thread detected via message composer EditText when ids change`() {
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(
                    className = "android.widget.EditText",
                    contentDescription = "Message…",
                ),
                NodeSnapshotFactory.node(text = "hey"),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.DM_THREAD)
    }

    @Test
    fun `dm thread with a shared reel preview is still DM_THREAD`() {
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(id = "com.instagram.android:id/direct_thread_toolbar"),
                NodeSnapshotFactory.node(
                    id = "com.instagram.android:id/message_composer",
                    className = "android.widget.EditText",
                ),
                NodeSnapshotFactory.node(
                    id = "com.instagram.android:id/clips_attachment_preview",
                    text = "shared reel preview",
                ),
            ),
        )
        assertThat(ReelDetector.detectScreen(root)).isEqualTo(ScreenType.DM_THREAD)
    }

    @Test
    fun `selected non reels bottom tab can be extracted`() {
        val tab = ReelDetector.extractSelectedBottomTab(NodeSnapshotFactory.homeFeedScreen())
        assertThat(tab).isEqualTo("home")
    }

    // ---- Signature extraction ----

    @Test
    fun `reel signature contains the author handle`() {
        val sig = ReelDetector.extractReelSignature(
            NodeSnapshotFactory.dmLaunchedReelScreen("alice"),
        )
        assertThat(sig).isNotNull()
        assertThat(sig).contains("alice")
    }

    @Test
    fun `non-clips profile nodes never produce a username signature`() {
        // Transitional trees carry the feed's / an ad's profile_name
        // nodes — latching those as the reel author made a legit DM
        // reel look like a swipe (field bug: latched "squarespace").
        val sig = ReelDetector.extractReelSignature(NodeSnapshotFactory.homeFeedScreen())
        if (sig != null) {
            assertThat(sig).doesNotContain("user:")
        }
    }

    @Test
    fun `different reel authors produce different signatures`() {
        val sigA = ReelDetector.extractReelSignature(
            NodeSnapshotFactory.dmLaunchedReelScreen("alice"),
        )
        val sigB = ReelDetector.extractReelSignature(
            NodeSnapshotFactory.dmLaunchedReelScreen("bob"),
        )
        assertThat(sigA).isNotEqualTo(sigB)
    }

    // ---- Diagnostics ----

    @Test
    fun `diagnostic summary on null is root=null`() {
        assertThat(ReelDetector.diagnosticSummary(null)).isEqualTo("root=null")
    }

    @Test
    fun `diagnostic summary surfaces reel ids when present`() {
        val diag = ReelDetector.diagnosticSummary(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(diag).contains("clips")
    }

    @Test
    fun `diagnostic summary falls back to sample ids and descriptions`() {
        val root = NodeSnapshotFactory.node(
            children = listOf(
                NodeSnapshotFactory.node(id = "com.instagram.android:id/some_other_container"),
                NodeSnapshotFactory.node(contentDescription = "Hello"),
            ),
        )
        val diag = ReelDetector.diagnosticSummary(root)
        // Without reel/dm keywords, fallback kicks in.
        assertThat(diag).isNotEqualTo("root=null")
        assertThat(diag).contains("some_other_container")
    }

    @Test
    fun `classify by class name detects reel viewer`() {
        val screen = ReelDetector.classifyByClassName(
            "com.instagram.clips.intf.ClipsViewerActivity"
        )
        assertThat(screen).isEqualTo(ScreenType.REEL_VIEWER)
    }

    @Test
    fun `classify by class name detects dm thread fragment`() {
        val screen = ReelDetector.classifyByClassName(
            "com.instagram.direct.wrappers.DirectThreadFragment"
        )
        assertThat(screen).isEqualTo(ScreenType.DM_THREAD)
    }

    @Test
    fun `classify by class name returns null for unrelated classes`() {
        assertThat(ReelDetector.classifyByClassName("android.widget.FrameLayout")).isNull()
        assertThat(ReelDetector.classifyByClassName(null)).isNull()
    }
}

package com.reelblock.app

import com.reelblock.app.NodeSnapshotFactory.node
import com.reelblock.app.SessionStateMachine.Action
import com.reelblock.app.SessionStateMachine.ReelOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-place switches, the user's own profile, and DM reels on first open. */
class PlaceSwitchesTest {

    private fun reel(sm: SessionStateMachine, origin: ReelOrigin, fromDm: Boolean = false): Action =
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:some.author", origin, fromDm)

    @Test
    fun reelsTabFollowsItsSwitch() {
        val sm = SessionStateMachine()
        assertEquals(Action.BLOCK_REEL, sm.handleScreen(ScreenType.REELS_TAB, null))
        sm.handleScreen(ScreenType.OTHER, null)
        sm.blockReelsTab = false
        assertEquals(Action.NONE, sm.handleScreen(ScreenType.REELS_TAB, null))
        assertEquals(Action.ALLOW_REEL, reel(sm, ReelOrigin.REELS_TAB))
    }

    @Test
    fun homeAndExploreFollowTheirSwitches() {
        val sm = SessionStateMachine()
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.HOME))
        sm.handleScreen(ScreenType.OTHER, null)
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.EXPLORE))
        sm.handleScreen(ScreenType.OTHER, null)

        sm.blockHomeReels = false
        assertEquals(Action.ALLOW_REEL, reel(sm, ReelOrigin.HOME))
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.EXPLORE))
        sm.handleScreen(ScreenType.OTHER, null)

        sm.blockExploreReels = false
        assertEquals(Action.ALLOW_REEL, reel(sm, ReelOrigin.EXPLORE))
    }

    @Test
    fun ownProfileIsAllowedByDefault() {
        val sm = SessionStateMachine()
        assertEquals(Action.ALLOW_REEL, reel(sm, ReelOrigin.OWN_PROFILE))
        // Swiping on through the user's own reels stays allowed.
        assertEquals(
            Action.ALLOW_REEL,
            sm.handleScreen(ScreenType.REEL_VIEWER, "user:other.author", ReelOrigin.OWN_PROFILE),
        )
        sm.handleScreen(ScreenType.OTHER, null)
        sm.blockOwnProfileReels = true
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.OWN_PROFILE))
    }

    @Test
    fun elsewhereIsAlwaysBlocked() {
        val sm = SessionStateMachine().apply {
            blockReelsTab = false
            blockHomeReels = false
            blockExploreReels = false
            blockOwnProfileReels = false
        }
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.ELSEWHERE))
    }

    @Test
    fun dmReelPlaysOnFirstOpenBeforeTheThreadWasRead() {
        // The reel was tapped before the thread's tree was ever read: the
        // only DM sign is the service's own (reply bar / Messages tab).
        val sm = SessionStateMachine()
        assertTrue(sm.dmEntryImminent(openedFromDm = true))
        assertEquals(Action.ALLOW_REEL, reel(sm, ReelOrigin.ELSEWHERE, fromDm = true))
        assertEquals(SessionStateMachine.Mode.DM_ALLOWED_REEL, sm.currentMode())
        // Swiping to the next reel is still blocked.
        assertEquals(Action.BLOCK_REEL, sm.onReelScroll())
    }

    @Test
    fun dmReelsSwitchOnBlocksThem() {
        val sm = SessionStateMachine().apply { allowDmReels = false }
        assertEquals(Action.BLOCK_REEL, reel(sm, ReelOrigin.ELSEWHERE, fromDm = true))
        // Not imminent while already blocking.
        assertFalse(sm.dmEntryImminent(openedFromDm = true))
    }

    // ---- ProfileContextTracker ----

    @Test
    fun profileTabOpensOnTheUsersOwnProfile() {
        val t = ProfileContextTracker()
        t.onTabTap(ReelDetector.TAB_PROFILE)
        assertEquals(ProfileContextTracker.Context.OWN_PROFILE, t.context)
        // Grid scrolled past the header: still theirs.
        t.onRead(ReelDetector.ProfilePageKind.NO_HEADER, ReelDetector.TAB_PROFILE)
        assertEquals(ProfileContextTracker.Context.OWN_PROFILE, t.context)
    }

    @Test
    fun someoneElsesProfileIsCarriedPastItsHeader() {
        val t = ProfileContextTracker()
        t.onTabTap(ReelDetector.TAB_SEARCH)
        assertEquals(ProfileContextTracker.Context.NONE, t.context)
        t.onRead(ReelDetector.ProfilePageKind.OTHER_PROFILE, ReelDetector.TAB_SEARCH)
        t.onRead(ReelDetector.ProfilePageKind.NO_HEADER, ReelDetector.TAB_SEARCH)
        assertEquals(ProfileContextTracker.Context.OTHER_PROFILE, t.context)

        // Over to Home and back: Search shows where it was left.
        t.onTabTap(ReelDetector.TAB_HOME)
        assertEquals(ProfileContextTracker.Context.NONE, t.context)
        t.onTabTap(ReelDetector.TAB_SEARCH)
        assertEquals(ProfileContextTracker.Context.OTHER_PROFILE, t.context)
        // Tapping the shown tab again goes back to its first page.
        t.onTabTap(ReelDetector.TAB_SEARCH)
        assertEquals(ProfileContextTracker.Context.NONE, t.context)
    }

    @Test
    fun ownProfileOpenedFromAnotherTab() {
        val t = ProfileContextTracker()
        t.onTabTap(ReelDetector.TAB_HOME)
        t.onRead(ReelDetector.ProfilePageKind.OWN, ReelDetector.TAB_HOME)
        assertEquals(ProfileContextTracker.Context.OWN_PROFILE, t.context)
        t.onRead(ReelDetector.ProfilePageKind.MAIN_SURFACE, ReelDetector.TAB_HOME)
        assertEquals(ProfileContextTracker.Context.NONE, t.context)
    }

    // ---- ReelDetector helpers ----

    private val ig = "com.instagram.android:id/"

    @Test
    fun profilePageKindReadsTheHeader() {
        val own = node(children = listOf(
            node(id = ig + "profile_header_container"),
            node(id = ig + "profile_header_create_button"),
        ))
        val other = node(children = listOf(
            node(id = ig + "profile_header_container"),
            node(id = ig + "profile_header_follow_button"),
        ))
        val gridOnly = node(children = listOf(node(id = ig + "profile_viewpager")))
        assertEquals(ReelDetector.ProfilePageKind.OWN, ReelDetector.profilePageKind(own))
        assertEquals(ReelDetector.ProfilePageKind.OTHER_PROFILE, ReelDetector.profilePageKind(other))
        assertEquals(ReelDetector.ProfilePageKind.NO_HEADER, ReelDetector.profilePageKind(gridOnly))
        assertEquals(
            ReelDetector.ProfilePageKind.MAIN_SURFACE,
            ReelDetector.profilePageKind(NodeSnapshotFactory.ig457MainScreen(ReelDetector.TAB_HOME)),
        )
    }

    @Test
    fun dmReplyBarMarksADmReel() {
        val plain = NodeSnapshotFactory.dmLaunchedReelScreen("some.author")
        val withBar = node(children = listOf(plain, node(id = ig + "reply_bar_container")))
        assertFalse(ReelDetector.hasDmReplyBar(plain))
        assertTrue(ReelDetector.hasDmReplyBar(withBar))
    }

    @Test
    fun homeFeedAloneHasNoStrongReelMarkers() {
        // A read of the feed list on its own must never count as a player,
        // or scrolling Home would bounce the user to the top.
        assertFalse(ReelDetector.hasStrongReelMarkers(NodeSnapshotFactory.homeFeedScreen()))
        assertTrue(ReelDetector.hasStrongReelMarkers(NodeSnapshotFactory.dmLaunchedReelScreen("some.author")))
    }

    // ---- FrontWindow ----

    private fun win(id: Int, kind: FrontWindow.Kind, focused: Boolean = false, active: Boolean = false) =
        FrontWindow.Win(id, kind, focused, active)

    @Test
    fun ourOwnCoverNeverCountsAsFront() {
        val front = FrontWindow.front(listOf(
            win(1, FrontWindow.Kind.IGNORED, focused = true),
            win(2, FrontWindow.Kind.APP, active = true),
        ))
        assertEquals(2, front?.id)
    }

    @Test
    fun shadeOrLockScreenInFrontIsAway() {
        val front = FrontWindow.front(listOf(
            win(1, FrontWindow.Kind.APP),
            win(2, FrontWindow.Kind.SYSTEM, focused = true),
        ))
        assertEquals(FrontWindow.Verdict.AWAY, FrontWindow.judge(front) { true })
    }

    @Test
    fun appInFrontIsJudgedByItsPackage() {
        val app = win(5, FrontWindow.Kind.APP, focused = true)
        assertEquals(FrontWindow.Verdict.INSTAGRAM, FrontWindow.judge(app) { true })
        assertEquals(FrontWindow.Verdict.AWAY, FrontWindow.judge(app) { false })
        assertEquals(FrontWindow.Verdict.UNKNOWN, FrontWindow.judge(app) { null })
        assertEquals(FrontWindow.Verdict.UNKNOWN, FrontWindow.judge(null) { true })
    }
}

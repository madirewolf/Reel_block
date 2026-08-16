package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * End-to-end tests that drive the detector and the state machine
 * together, the same way the Accessibility Service does in production.
 */
class DetectorStateMachineIntegrationTest {

    private lateinit var sm: SessionStateMachine

    @Before
    fun setUp() {
        sm = SessionStateMachine()
    }

    private fun step(snapshot: NodeSnapshot?): SessionStateMachine.Action {
        val screen = ReelDetector.detectScreen(snapshot)
        val sig = ReelDetector.extractReelSignature(snapshot)
        return sm.handleScreen(screen, sig)
    }

    @Test
    fun `user opens reels tab - blocked`() {
        val action = step(NodeSnapshotFactory.reelsTabScreen())
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `user opens DM, taps shared reel, watches same reel - allowed`() {
        step(NodeSnapshotFactory.dmInboxScreen())
        step(NodeSnapshotFactory.dmThreadScreen())
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)

        // Continuing to view the same reel stays allowed.
        val again = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(again).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `user opens DM, taps shared reel, swipes to next - blocked by signature change`() {
        // Even without a scroll event (a swipe inside the scroll grace
        // window is invisible to onReelScroll), the new author's
        // username signature is enough to detect the swipe.
        step(NodeSnapshotFactory.dmInboxScreen())
        step(NodeSnapshotFactory.dmThreadScreen())
        step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("bob"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `dm option off - even a dm-shared reel is blocked`() {
        sm.allowDmReels = false
        step(NodeSnapshotFactory.dmInboxScreen())
        step(NodeSnapshotFactory.dmThreadScreen())
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `user taps reels tab directly while bottom nav visible - blocked even if previous was DM`() {
        step(NodeSnapshotFactory.dmThreadScreen())
        val action = step(NodeSnapshotFactory.reelsTabScreen())
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `return path - DM, reel, back to DM, different reel in same DM - allowed`() {
        step(NodeSnapshotFactory.dmThreadScreen())
        step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        step(NodeSnapshotFactory.dmThreadScreen())
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("carol"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `scroll signal short-circuits signature stability`() {
        step(NodeSnapshotFactory.dmThreadScreen())
        step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        // Even though the snapshot-based reel signature hasn't changed
        // yet (captions still loading), a scroll event means the user
        // has physically swiped — block right away.
        val action = sm.onReelScroll()
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `watching a story then opening a reel is blocked - no fake dm context`() {
        // Field bug: the story viewer classified DM_THREAD, so a reel
        // opened right after a story was allowed as if a friend had
        // sent it. The story must classify OTHER and leave no DM trace.
        step(NodeSnapshotFactory.homeFeedScreen())
        sm.noteStableNonDmSurface()
        step(NodeSnapshotFactory.storyViewerScreen())
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `story opened from a dm thread does not break the dm reel exemption`() {
        // A story classifies OTHER, which must not wipe the DM_THREAD
        // context — backing out of the story and tapping the shared
        // reel should still be allowed.
        step(NodeSnapshotFactory.dmThreadScreen())
        step(NodeSnapshotFactory.storyViewerScreen())
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `stable explore screen clears dm context before opening a reel`() {
        step(NodeSnapshotFactory.dmThreadScreen())
        step(NodeSnapshotFactory.homeFeedScreen())
        sm.noteStableNonDmSurface()
        val action = step(NodeSnapshotFactory.dmLaunchedReelScreen("alice"))
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }
}

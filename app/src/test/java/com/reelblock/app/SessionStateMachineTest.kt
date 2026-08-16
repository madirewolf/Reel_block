package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class SessionStateMachineTest {

    private lateinit var sm: SessionStateMachine

    @Before
    fun setUp() {
        sm = SessionStateMachine()
    }

    // ---- BLOCK path ----

    @Test
    fun `reels tab is blocked immediately`() {
        val action = sm.handleScreen(ScreenType.REELS_TAB, reelSignature = "user:someone")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.BLOCKING)
    }

    @Test
    fun `reel viewer reached from home feed is blocked`() {
        sm.handleScreen(ScreenType.OTHER, null) // home
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:x")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `reel viewer reached from dm inbox is blocked (not a specific DM thread)`() {
        sm.handleScreen(ScreenType.DM_INBOX, null)
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:x")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    // ---- ALLOW path ----

    @Test
    fun `reel viewer reached from a dm thread is allowed`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.DM_ALLOWED_REEL)
        assertThat(sm.currentAllowedSignature()).isEqualTo("user:alice")
    }

    @Test
    fun `same reel with late-loading signature latches on first non-null sig`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        // First reel viewer event has no signature yet.
        sm.handleScreen(ScreenType.REEL_VIEWER, null)
        assertThat(sm.currentAllowedSignature()).isNull()
        // Signature loads — we latch it.
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        assertThat(sm.currentAllowedSignature()).isEqualTo("user:alice")
    }

    @Test
    fun `continued updates for the same reel stay allowed`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        val a1 = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        val a2 = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(a1).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        assertThat(a2).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    // ---- Swipe-to-next-reel ----

    @Test
    fun `swipe to a different author blocks via signature change`() {
        // Both signatures are stable user: ones, so a change means the
        // user really swiped — even if the scroll event was missed
        // (e.g. a swipe inside the scroll grace window).
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:bob")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.BLOCKING)
    }

    @Test
    fun `coarse txt signature drift does not block`() {
        // txt: fallback signatures hash caption text that loads
        // frame-by-frame — drift between them must NOT be read as a
        // swipe or we'd kick users out of legitimately allowed reels.
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "txt:100")
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "txt:200")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.DM_ALLOWED_REEL)
    }

    @Test
    fun `txt latch upgrades to username then catches the swipe`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        // Entered while only the coarse fallback was available.
        sm.handleScreen(ScreenType.REEL_VIEWER, "txt:100")
        // Username loads for the same reel — upgrade, still allowed.
        val upgraded = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(upgraded).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        assertThat(sm.currentAllowedSignature()).isEqualTo("user:alice")
        // Now a different author shows up — that's a swipe.
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:bob")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    // ---- The "allow reels from DMs" option ----

    @Test
    fun `dm reel is blocked when the dm option is off`() {
        sm.allowDmReels = false
        sm.handleScreen(ScreenType.DM_THREAD, null)
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `disabling the dm option mid-watch blocks the current reel`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        val allowed = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(allowed).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
        sm.allowDmReels = false
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `re-enabling the dm option restores the exemption`() {
        sm.allowDmReels = false
        sm.handleScreen(ScreenType.DM_THREAD, null)
        assertThat(sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice"))
            .isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
        sm.allowDmReels = true
        // Back to the DM thread, open the reel again — allowed now.
        sm.handleScreen(ScreenType.DM_THREAD, null)
        assertThat(sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice"))
            .isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `scroll event while DM-allowed blocks`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        val action = sm.onReelScroll()
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `scroll event when idle does nothing`() {
        val action = sm.onReelScroll()
        assertThat(action).isEqualTo(SessionStateMachine.Action.NONE)
    }

    // ---- Context preservation across OTHER/UNKNOWN ----

    @Test
    fun `transient OTHER between DM thread and reel viewer still allows`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.OTHER, null)     // transient load
        sm.handleScreen(ScreenType.UNKNOWN, null)   // more loading
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `stable non dm surface clears dm context`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.OTHER, null)
        sm.noteStableNonDmSurface()
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `navigating back to DM thread then into another reel reallows once`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        sm.handleScreen(ScreenType.DM_THREAD, null) // back out
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:bob")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    @Test
    fun `from reels tab blocked, navigating to DM and opening reel is allowed`() {
        sm.handleScreen(ScreenType.REELS_TAB, "user:doesntmatter")
        sm.handleScreen(ScreenType.DM_INBOX, null)
        sm.handleScreen(ScreenType.DM_THREAD, null)
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.ALLOW_REEL)
    }

    // ---- Reset/recycle ----

    @Test
    fun `leaving the reel viewer back to DM thread resets mode`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        sm.handleScreen(ScreenType.DM_THREAD, null)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.IDLE)
        assertThat(sm.currentAllowedSignature()).isNull()
    }

    @Test
    fun `reset wipes all state`() {
        sm.handleScreen(ScreenType.DM_THREAD, null)
        sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        sm.reset()
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.IDLE)
        assertThat(sm.currentAllowedSignature()).isNull()
        assertThat(sm.currentPrevScreen()).isEqualTo(ScreenType.UNKNOWN)
    }

    @Test
    fun `while blocking, subsequent reel viewer events stay blocked`() {
        sm.handleScreen(ScreenType.REELS_TAB, "x")
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.BLOCKING)
        val action = sm.handleScreen(ScreenType.REEL_VIEWER, "user:alice")
        assertThat(action).isEqualTo(SessionStateMachine.Action.BLOCK_REEL)
    }

    @Test
    fun `returning to a normal screen after blocking clears blocking mode`() {
        sm.handleScreen(ScreenType.REELS_TAB, "x")
        val action = sm.handleScreen(ScreenType.OTHER, null)
        assertThat(action).isEqualTo(SessionStateMachine.Action.NONE)
        assertThat(sm.currentMode()).isEqualTo(SessionStateMachine.Mode.IDLE)
    }
}

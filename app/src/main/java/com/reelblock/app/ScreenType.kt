package com.reelblock.app

/**
 * A coarse classification of the Instagram screen currently on top.
 *
 * The Accessibility Service cannot read Instagram's internal navigation,
 * so we infer the screen from the visible view hierarchy. A small
 * vocabulary is enough for the blocker: we only need to know whether we
 * are looking at a reel player, and whether the user had just been in
 * a DM when they entered it.
 */
enum class ScreenType {
    /** Default — before any event has been processed. */
    UNKNOWN,

    /** Some Instagram screen we don't classify (profile, explore, etc). */
    OTHER,

    /** The Reels tab in the bottom navigation is currently selected. Always blocked. */
    REELS_TAB,

    /**
     * A fullscreen reel player is visible. Could have been entered from
     * the Reels tab OR from a DM; the SessionStateMachine decides which.
     */
    REEL_VIEWER,

    /** The inbox of DM threads. */
    DM_INBOX,

    /** An individual DM conversation. */
    DM_THREAD,
}

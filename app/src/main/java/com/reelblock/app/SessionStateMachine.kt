package com.reelblock.app

/**
 * Pure state machine that decides BLOCK vs ALLOW based on the sequence
 * of screens Instagram shows.
 *
 * This is intentionally isolated from anything Android so it can be
 * exhaustively unit-tested on the JVM. The Accessibility Service is a
 * thin driver: it classifies the current screen, hands the result here,
 * and acts on the returned [Action].
 *
 * Rules:
 *  1. The Reels tab is blocked while [blockReelsTab] is on.
 *  2. A reel opened from DMs (see [dmEntryImminent]) is allowed while
 *     [allowDmReels] is on — only that reel: a swipe to the next one is
 *     blocked.
 *  3. If the username signature changes while we are in "DM-allowed"
 *     mode (the user swiped to another author's reel), we block.
 *     Coarse `txt:` fallback signatures never trigger this — they
 *     drift as captions load frame-by-frame.
 *  4. Any other reel follows the switch for where it was opened
 *     ([ReelOrigin]): Home, Search & Explore, or the user's own profile.
 *     Anywhere else (someone else's profile, an audio page, ...) it is
 *     always blocked.
 *  5. A scroll inside the reel viewer while in DM-allowed mode counts
 *     as a swipe — external callers use [onReelScroll] for that.
 */
class SessionStateMachine {

    /**
     * User-facing option: when false, a reel opened from a DM gets no
     * exemption — every reel viewer is blocked, period. Mirrors
     * [SettingsStore.allowDmReels]; the service keeps it in sync.
     */
    var allowDmReels: Boolean = true

    /** User-facing switches, one per place; the service keeps them in sync. */
    var blockReelsTab: Boolean = true
    var blockHomeReels: Boolean = true
    var blockExploreReels: Boolean = true
    var blockOwnProfileReels: Boolean = false

    /** Where a reel viewer was opened from, apart from DMs. [REELS_TAB]
     *  is a read of the Reels tab's own player that missed the tab bar. */
    enum class ReelOrigin { REELS_TAB, HOME, EXPLORE, OWN_PROFILE, ELSEWHERE }

    enum class Mode {
        /** Not currently tracking a reel-watch session. */
        IDLE,

        /** Watching a reel that was legitimately opened from a DM thread. */
        DM_ALLOWED_REEL,

        /** We have blocked; stay blocked until the user leaves the reel player. */
        BLOCKING,
    }

    enum class Action { ALLOW_REEL, BLOCK_REEL, NONE }

    /** When the signature first changes from null to something, we "lock it in". */
    private var mode: Mode = Mode.IDLE

    /**
     * Last screen we classified with confidence. OTHER / UNKNOWN do NOT
     * overwrite this — we want "the real previous screen", not transient
     * loading states, when deciding if the reel viewer came from a DM.
     */
    private var lastKnownScreen: ScreenType = ScreenType.UNKNOWN

    /** Signature of the reel we decided to allow, if any. */
    private var allowedReelSignature: String? = null

    fun currentMode(): Mode = mode
    fun currentAllowedSignature(): String? = allowedReelSignature
    fun currentPrevScreen(): ScreenType = lastKnownScreen

    /**
     * Called when the service is confident the user is on a stable
     * non-DM Instagram surface such as Home, Explore, or Profile. That
     * clears any stale "came from DM" context so opening a reel from
     * those screens is blocked.
     *
     * Returns true when this ended a block (mode was BLOCKING), so the
     * caller knows the navigation landed and the cover can come down.
     */
    fun noteStableNonDmSurface(): Boolean {
        val wasBlocking = mode == Mode.BLOCKING
        resetSession()
        lastKnownScreen = ScreenType.OTHER
        return wasBlocking
    }

    /**
     * Called when the classifier reports a new screen. Always call this
     * for every meaningful accessibility event so the state machine can
     * track transitions.
     */
    fun handleScreen(
        newScreen: ScreenType,
        reelSignature: String?,
        origin: ReelOrigin = ReelOrigin.ELSEWHERE,
        openedFromDm: Boolean = false,
    ): Action {
        val action = when (newScreen) {
            ScreenType.REELS_TAB -> if (blockReelsTab) {
                enterBlocking()
            } else {
                if (mode == Mode.BLOCKING) resetSession()
                Action.NONE
            }
            ScreenType.REEL_VIEWER -> handleReelViewer(reelSignature, origin, openedFromDm)
            ScreenType.DM_THREAD, ScreenType.DM_INBOX -> {
                // User is navigating in Direct — that resets any prior
                // blocking state, so the next reel viewer can be a fresh
                // "allowed from DM" session.
                resetSession()
                Action.NONE
            }
            ScreenType.OTHER, ScreenType.UNKNOWN -> {
                // Landing on any non-reel Instagram surface ends an
                // active blocking session.
                if (newScreen == ScreenType.OTHER && mode == Mode.BLOCKING) {
                    resetSession()
                }
                // Don't treat OTHER/UNKNOWN as a context change. These
                // fire during transitions (content loading, brief
                // fragment swaps) and would otherwise wipe out the
                // "just came from DM" history we need to allow a reel.
                Action.NONE
            }
        }
        if (newScreen != ScreenType.OTHER && newScreen != ScreenType.UNKNOWN) {
            lastKnownScreen = newScreen
        }
        return action
    }

    /**
     * Explicit scroll signal (TYPE_VIEW_SCROLLED) while a reel viewer is
     * on top. In DM-allowed mode this is how we detect a swipe to the
     * next reel and block immediately, without having to wait for the
     * signature to update.
     */
    fun onReelScroll(): Action {
        return if (mode == Mode.DM_ALLOWED_REEL) {
            lastKnownScreen = ScreenType.REEL_VIEWER
            enterBlocking()
        } else {
            Action.NONE
        }
    }

    /** Wipe state — called when the target app is left entirely. */
    fun reset() {
        mode = Mode.IDLE
        lastKnownScreen = ScreenType.UNKNOWN
        allowedReelSignature = null
    }

    private fun resetSession() {
        mode = Mode.IDLE
        allowedReelSignature = null
    }

    private fun enterBlocking(): Action {
        mode = Mode.BLOCKING
        allowedReelSignature = null
        return Action.BLOCK_REEL
    }

    /**
     * True when the next reel viewer would count as opened from DMs:
     * a DM thread was the last screen, or the service saw DM signs of its
     * own ([openedFromDm]: the reply bar under the reel, or the Messages
     * tab being the last tab). An inbox read alone does not count — the
     * Home feed's DM button can read as an inbox.
     */
    fun dmEntryImminent(openedFromDm: Boolean = false): Boolean =
        mode == Mode.IDLE && (openedFromDm || lastKnownScreen == ScreenType.DM_THREAD)

    private fun handleReelViewer(sig: String?, origin: ReelOrigin, openedFromDm: Boolean): Action {
        // Case 1: we are already blocking this reel session and the
        // viewer is still on top — keep blocking (the service will be
        // pressing BACK in a loop).
        if (mode == Mode.BLOCKING) {
            return Action.BLOCK_REEL
        }

        // Case 2: Already in DM-allowed mode. The explicit scroll signal
        // ([onReelScroll], fired from TYPE_VIEW_SCROLLED after the grace
        // window) is the primary swipe detector, but it has a hole: a
        // swipe inside the grace window is invisible to it, and the next
        // reel would stay allowed forever. So we ALSO compare username
        // signatures — but only when both the latched and the incoming
        // signature are stable `user:` ones. Coarse `txt:` fallbacks are
        // excluded because caption/audio text loads frame-by-frame and
        // tree traversal order is not stable on Samsung; comparing them
        // used to kick users out of legitimately DM-opened reels a beat
        // after they started playing.
        if (mode == Mode.DM_ALLOWED_REEL) {
            // The option was turned off mid-watch.
            if (!allowDmReels) return enterBlocking()
            val latched = allowedReelSignature
            when {
                latched.isNullOrBlank() -> {
                    // Signature loaded late — latch the first one we see.
                    if (!sig.isNullOrBlank()) allowedReelSignature = sig
                }
                !isStableSignature(latched) && isStableSignature(sig) -> {
                    // Upgrade a coarse txt: latch to the username sig the
                    // moment it appears — from here on swipes are caught.
                    allowedReelSignature = sig
                }
                isStableSignature(latched) && isStableSignature(sig) && latched != sig -> {
                    // Different author = the user swiped to another reel.
                    return enterBlocking()
                }
            }
            return Action.ALLOW_REEL
        }

        // Case 3: opened from DMs. [openedFromDm] matters: a thread takes
        // ~1.5 s to read, and a reel tapped before that used to be
        // blocked on the first try.
        if (dmEntryImminent(openedFromDm)) {
            if (!allowDmReels) return enterBlocking()
            mode = Mode.DM_ALLOWED_REEL
            allowedReelSignature = sig
            return Action.ALLOW_REEL
        }

        // Case 4: the switch for where it was opened. An allowed reel
        // keeps the mode IDLE, so swiping on from it stays allowed.
        val block = when (origin) {
            ReelOrigin.REELS_TAB -> blockReelsTab
            ReelOrigin.HOME -> blockHomeReels
            ReelOrigin.EXPLORE -> blockExploreReels
            ReelOrigin.OWN_PROFILE -> blockOwnProfileReels
            ReelOrigin.ELSEWHERE -> true
        }
        return if (block) enterBlocking() else Action.ALLOW_REEL
    }

    private fun isStableSignature(sig: String?): Boolean =
        sig?.startsWith(ReelDetector.SIGNATURE_USER_PREFIX) == true
}

package com.reelblock.app

/**
 * Decides when the user has left Instagram, so session state can reset.
 *
 * The service is registered for Instagram's packages only, so Android
 * never delivers it an event from the launcher or another app — leaving
 * Instagram is silent. The service therefore polls the on-screen window
 * list while a session is live and feeds each poll's [Verdict] in here.
 *
 * Pure Kotlin so the rules are unit-tested on the JVM:
 *  - Instagram owns an application window -> it is on screen.
 *  - Another app owns an application window and Instagram owns none ->
 *    a miss. [requiredMisses] consecutive misses = the user left.
 *  - Only system windows (notification shade, lock screen) or windows
 *    whose tree is unreadable -> no evidence either way. Pulling down
 *    the shade or locking the phone mid-reel is not leaving Instagram.
 *  - A departure only counts after Instagram was actually seen on
 *    screen ("armed"). Instagram keeps trickling a few events while it
 *    sits behind the launcher; without the arming rule each trickle
 *    would start a watch that "detects" a departure that already
 *    happened.
 */
class InstagramPresence(
    private val instagramPackages: Set<String>,
    private val requiredMisses: Int = 2,
    /** Polls without ever seeing Instagram before the watch gives up. */
    private val giveUpAfter: Int = 5,
) {

    enum class Verdict { INSTAGRAM, OTHER_APP, UNKNOWN }

    enum class Decision {
        /** Poll again. */
        KEEP_WATCHING,

        /** The user left Instagram: reset session state, stop polling. */
        LEFT,

        /** Nothing to watch (Instagram never seen on screen): stop. */
        STOP,
    }

    /** One on-screen window, reduced to what the decision needs. */
    data class WindowSummary(val isApplication: Boolean, val packageName: String?)

    private var armed = false
    private var misses = 0
    private var unarmedPolls = 0

    fun classify(windows: List<WindowSummary>): Verdict {
        val apps = windows.filter { it.isApplication && !it.packageName.isNullOrBlank() }
        return when {
            apps.any { it.packageName in instagramPackages } -> Verdict.INSTAGRAM
            apps.isNotEmpty() -> Verdict.OTHER_APP
            else -> Verdict.UNKNOWN
        }
    }

    /** A watch is (re)starting after an Instagram event. */
    fun onWatchStart() {
        unarmedPolls = 0
    }

    /** The service read a live Instagram view tree — Instagram is
     *  definitely on screen (a covered app's tree is unreachable). */
    fun onInstagramEvidence() {
        armed = true
        misses = 0
    }

    fun onCheck(verdict: Verdict): Decision {
        if (verdict == Verdict.INSTAGRAM) {
            onInstagramEvidence()
            return Decision.KEEP_WATCHING
        }
        if (!armed) {
            unarmedPolls += 1
            return if (unarmedPolls >= giveUpAfter) Decision.STOP else Decision.KEEP_WATCHING
        }
        if (verdict == Verdict.OTHER_APP) {
            misses += 1
            if (misses >= requiredMisses) {
                armed = false
                misses = 0
                return Decision.LEFT
            }
        }
        return Decision.KEEP_WATCHING
    }
}

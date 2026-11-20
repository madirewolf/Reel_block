package com.reelblock.app

/**
 * Which app is in front, so the covers come down the moment the user
 * leaves Instagram: Home, recents, another app, the notification shade
 * or the lock screen.
 *
 * The front window is the one with input focus, else the active one.
 * Our own covers (accessibility overlays), the keyboard and the
 * split-screen divider never count. Pure Kotlin so the rules are
 * unit-tested on the JVM.
 */
object FrontWindow {

    enum class Verdict {
        /** Instagram has focus. */
        INSTAGRAM,

        /** Something else has focus: the covers must go. */
        AWAY,

        /** Can't tell (no focused window, or its tree isn't readable yet). */
        UNKNOWN,
    }

    enum class Kind {
        /** An app's window: Instagram, the launcher, another app. */
        APP,

        /** A system window: the notification shade, the lock screen. */
        SYSTEM,

        /** Never in front for this purpose: overlays, keyboard, divider. */
        IGNORED,
    }

    data class Win(val id: Int, val kind: Kind, val focused: Boolean, val active: Boolean)

    fun front(windows: List<Win>): Win? {
        val candidates = windows.filter { it.kind != Kind.IGNORED }
        return candidates.firstOrNull { it.focused } ?: candidates.firstOrNull { it.active }
    }

    /**
     * [isInstagram] says whether an app window is Instagram's; null when
     * its tree can't be read.
     */
    fun judge(front: Win?, isInstagram: (Win) -> Boolean?): Verdict = when {
        front == null -> Verdict.UNKNOWN
        front.kind == Kind.SYSTEM -> Verdict.AWAY
        else -> when (isInstagram(front)) {
            true -> Verdict.INSTAGRAM
            false -> Verdict.AWAY
            null -> Verdict.UNKNOWN
        }
    }
}

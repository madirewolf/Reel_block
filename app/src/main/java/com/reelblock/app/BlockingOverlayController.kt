package com.reelblock.app

import android.content.Context

/**
 * Previously drew a short "Reeled in" banner over the top of Instagram
 * on every block. The user asked for it to go away — it cluttered more
 * than it helped on Samsung (animation timing fought with the nav
 * action, and the banner would appear *after* the user had already
 * moved on). Kept as a no-op shim so the service's call sites don't
 * have to change, and so we can re-add a cleaner affordance later
 * without plumbing a new type through.
 */
class BlockingOverlayController(@Suppress("UNUSED_PARAMETER") context: Context) {
    fun showBlocked(@Suppress("UNUSED_PARAMETER") visibleFor: Long = 0L) = Unit
    fun hide() = Unit
}

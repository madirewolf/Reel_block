package com.reelblock.app

/**
 * Remembers the bottom-tab click the service itself just performed.
 *
 * ACTION_CLICK on Instagram's Home tab fires a TYPE_VIEW_CLICKED echo
 * whether or not Instagram actually switched tabs. The nav-event fast
 * path read that echo as "the user tapped Home": it dropped the session
 * to IDLE, which stopped the verification retries. When Instagram
 * ignored the click (a tab switch still in flight), the user was left
 * on the Reels tab once the cover timed out. The echo proves nothing;
 * the tree, or the TYPE_VIEW_SELECTED Instagram sends when the tab
 * really changes, decides whether navigation landed.
 */
class SelfTabClickEcho(private val windowMs: Long = ECHO_WINDOW_MS) {

    private var pendingTab: String? = null
    private var clickedAt = 0L

    /** Call just before the service clicks [tabLabel] ("home", …). */
    fun noteSelfClick(tabLabel: String, now: Long) {
        pendingTab = tabLabel
        clickedAt = now
    }

    /** The click was not delivered; nothing will echo. */
    fun clear() {
        pendingTab = null
    }

    /**
     * True when this event is the echo of our own click, which is then
     * consumed: a later real tap on the same tab counts again. Only
     * click events echo; a selection change is real evidence.
     */
    fun consumeIfEcho(isClickEvent: Boolean, eventTabLabel: String?, now: Long): Boolean {
        val tab = pendingTab ?: return false
        if (now - clickedAt > windowMs) {
            pendingTab = null
            return false
        }
        if (!isClickEvent || eventTabLabel != tab) return false
        pendingTab = null
        return true
    }

    companion object {
        const val ECHO_WINDOW_MS = 1_500L
    }
}

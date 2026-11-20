package com.reelblock.app

/**
 * Remembers whose profile the user is on, so a reel opened from it can
 * follow the "Your profile" switch.
 *
 * The profile header (with its own-profile or Follow buttons) is only in
 * the tree near the top of the page; once the grid scrolls past it the
 * page only shows `profile_viewpager`. So the answer from the last
 * header read is kept per bottom tab, and carried while the header is
 * out of view.
 *
 * Feed it only whole-window reads: a read of one scrolled list says
 * nothing about the page around it.
 */
class ProfileContextTracker {

    enum class Context { OWN_PROFILE, OTHER_PROFILE, NONE }

    var context: Context = Context.NONE
        private set

    private var lastTab: String? = null
    private val byTab = mutableMapOf<String, Context>()

    /** A whole-window read of a non-reel screen. */
    fun onRead(kind: ReelDetector.ProfilePageKind, selectedTab: String?) {
        val tabChanged = selectedTab != null && selectedTab != lastTab
        context = when (kind) {
            ReelDetector.ProfilePageKind.OWN -> Context.OWN_PROFILE
            ReelDetector.ProfilePageKind.OTHER_PROFILE -> Context.OTHER_PROFILE
            ReelDetector.ProfilePageKind.MAIN_SURFACE -> Context.NONE
            ReelDetector.ProfilePageKind.NO_HEADER ->
                if (tabChanged) byTab[selectedTab] ?: rootOf(selectedTab) else context
            ReelDetector.ProfilePageKind.NEUTRAL -> context
        }
        if (selectedTab != null) {
            lastTab = selectedTab
            byTab[selectedTab] = context
        }
    }

    /**
     * A tap on a bottom tab. Tapping the tab already shown goes back to
     * its first page; tapping another tab shows wherever that tab was left.
     */
    fun onTabTap(tab: String) {
        context = if (tab == lastTab) rootOf(tab) else byTab[tab] ?: rootOf(tab)
        lastTab = tab
        byTab[tab] = context
    }

    fun reset() {
        context = Context.NONE
        lastTab = null
        byTab.clear()
    }

    /** The first page of a tab: the Profile tab opens on the user's own profile. */
    private fun rootOf(tab: String?): Context =
        if (tab == ReelDetector.TAB_PROFILE) Context.OWN_PROFILE else Context.NONE
}

package com.reelblock.app

/**
 * Heuristic classifier for the currently-visible Instagram screen.
 *
 * Instagram obfuscates and rotates resource IDs between releases, so
 * the detector uses a layered signal strategy and treats any one hit
 * as evidence rather than requiring an exact match. Order matters:
 * the Reels-tab check wins over the generic reel-viewer check so a
 * reel seen inside the main feed is always blocked.
 *
 * The signals, strongest first:
 *   1. `isSelected=true` on a node whose contentDescription starts with
 *      "Reels" — this is how Android tells TalkBack "the Reels tab in
 *      the bottom nav is currently focused".
 *   2. Any resource id containing the substring "clips" — Instagram's
 *      internal name for reels, stable across releases for years.
 *   3. 3+ reel-action content descriptions (Like, Comments, Share, …)
 *      present alongside a video surface — catches releases that
 *      rename the "clips" resource ids.
 *   4. 4+ reel-action descriptions regardless of video surface —
 *      loosens signal (3) when the framework hides SurfaceView.
 *
 * Every signal is English-locale-dependent for the content-description
 * path; the ID path works in any locale.
 */
object ReelDetector {

    /** Prefix of signatures derived from the reel author's username —
     *  the only signature kind stable enough for the state machine to
     *  compare when deciding "did the user swipe to another reel?". */
    const val SIGNATURE_USER_PREFIX = "user:"

    data class TreeDebug(
        val nodeCount: Int,
        val maxDepth: Int,
        val interestingIds: List<String>,
        val sampledIds: List<String>,
        val sampledDescriptions: List<String>,
    )

    /**
     * Resource-id *segments* (the part after `:id/`) that unambiguously
     * identify the fullscreen reel viewer. Kept intentionally minimal —
     * every entry was checked against a live Instagram home tree to make
     * sure the home screen's stories-tray IDs (`reel_viewer_front_avatar`,
     * `reels_tray_container`, `reel_empty_badge`, …) do NOT match. Those
     * were poisoning the previous substring-based detector and causing
     * the home feed to be classified as REELS_TAB.
     */
    private val REEL_VIEWER_ID_SEGMENTS = setOf(
        "clips_viewer",
        "clips_viewer_view_pager",
    )
    private const val REEL_VIEWER_ID_PREFIX = "clips_viewer_"

    /** Content-description substrings that appear on the action column
     *  to the right of a reel. Matched case-insensitively; we expect
     *  Instagram's default English strings. */
    private val REEL_ACTION_DESC_HINTS = listOf(
        "like", "unlike",
        "comment", "comments", "view comments",
        "share",
        "save", "saved",
        "audio", "music", "original audio",
        "remix",
        "reel options",
        "mute", "unmute",
    )

    private val VIDEO_CLASS_HINTS = listOf(
        "SurfaceView", "TextureView", "VideoView",
    )

    /**
     * Ids that identify the STORY viewer. Instagram's internal name for
     * stories is "reel" (singular — the stories tray is `reels_tray`),
     * while actual Reels are "clips". A Galaxy S24 log showed the story
     * viewer classified DM_THREAD because its "Send message" reply box
     * matches the DM-composer fallback — which resets blocking state
     * AND plants fake "came from DM" context that would allow the next
     * reel. Stories are friend content; they are never blocked.
     */
    private val STORY_VIEWER_ID_PREFIXES = listOf(
        "reel_viewer",
        "reel_item_toolbar",
        "reel_sticker",
        "reel_view_group",
    )

    private val DM_THREAD_ID_SUBSTRINGS = listOf(
        "direct_thread",
        "thread_view",
        "direct_message",
        "message_composer",
        "row_thread",
        "thread_detail",
        "thread_toolbar",
    )

    private val DM_INBOX_ID_SUBSTRINGS = listOf(
        "direct_inbox",
        "inbox_",
        "thread_list",
        "threads_list",
    )

    /**
     * Instant classification from the event itself — every tab tap
     * fires TYPE_VIEW_CLICKED (and/or TYPE_VIEW_SELECTED) whose
     * contentDescription is the tab label. This is *more reliable*
     * than either class-name or tree-based classification in the
     * ambiguous window after a tab switch, when two bottom-nav
     * buttons can both report `isSelected=true` for 300–700 ms on
     * Samsung: the event unambiguously names which tab the user
     * actually pressed.
     *
     * Returns null for events that are not a bottom-nav tab tap —
     * callers should fall back to [classifyByClassName] and finally
     * tree-based [detectScreen].
     */
    fun classifyByBottomNavEvent(
        eventType: Int,
        eventContentDescription: CharSequence?,
    ): ScreenType? {
        if (eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return null
        }
        val desc = eventContentDescription?.toString()?.trim()?.lowercase() ?: return null
        return when {
            desc == "reels" || desc.startsWith("reels,") ||
                desc.startsWith("reels ") || desc.startsWith("reels tab")
                -> ScreenType.REELS_TAB
            desc == "home" || desc.startsWith("home,") ||
                desc.startsWith("home ") || desc.startsWith("home tab") ||
                desc == "search" || desc.startsWith("search,") ||
                desc.startsWith("search ") || desc.startsWith("search tab") ||
                desc == "profile" || desc.startsWith("profile,") ||
                desc.startsWith("profile ") || desc.startsWith("profile tab") ||
                desc == "notifications" || desc.startsWith("notifications,") ||
                desc.startsWith("notifications ") || desc.startsWith("notifications tab")
                -> ScreenType.OTHER
            else -> null
        }
    }

    /**
     * Instant classification from `AccessibilityEvent.getClassName()` —
     * available on TYPE_WINDOW_STATE_CHANGED *before* the view tree is
     * queryable. Instagram's activity / fragment class names carry the
     * screen identity in plain English ("ClipsViewerActivity",
     * "DirectThreadFragment", …) so a handful of substring checks classify
     * the screen in microseconds with zero tree traversal.
     *
     * Returns null when the class name is missing or unrecognized — caller
     * should then fall back to the tree-based [detectScreen].
     */
    fun classifyByClassName(className: String?): ScreenType? {
        val cls = className?.toString()?.lowercase() ?: return null
        // Order matters: reel-viewer signals beat DM signals (the DM-thread
        // activity stays in the back stack when a reel viewer fragment
        // mounts on top, and we want the reel signal to win).
        if (cls.contains("clipsviewer") ||
            cls.contains("clips_viewer") ||
            cls.contains("reelsviewer") ||
            cls.contains("reelviewer")
        ) return ScreenType.REEL_VIEWER
        if (cls.contains("clipsfragment") ||
            cls.contains("clipstabfragment") ||
            cls.contains("reelstab")
        ) return ScreenType.REELS_TAB
        if (cls.contains("directthread") ||
            cls.contains("directmessagethread") ||
            cls.contains("threadfragment") ||
            cls.contains("threaddetail")
        ) return ScreenType.DM_THREAD
        if (cls.contains("directinbox") ||
            cls.contains("inboxfragment") ||
            cls.contains("directprivate")
        ) return ScreenType.DM_INBOX
        return null
    }

    fun detectScreen(root: NodeSnapshot?): ScreenType {
        if (root == null) return ScreenType.UNKNOWN
        val nodes = root.flatten().toList()

        // 1. Authoritative: which bottom-nav tab is currently selected?
        //    [extractSelectedBottomTabFromNodes] *prefers a non-reels
        //    tab* when multiple `isSelected=true` nodes appear in the
        //    tree — which does happen on Samsung during rapid tab
        //    switches where the previously-selected Reels button has
        //    not yet had its isSelected state cleared before the newly-
        //    selected tab reports as selected. Without that preference
        //    a tap on Search produced REELS_TAB, triggering a bogus
        //    home-tab click.
        //
        //    With a non-Reels tab selected, thread markers still win —
        //    the composer / direct_thread ids never appear on main-tab
        //    surfaces, and entering a thread can briefly leave a stale
        //    tab selection in the tree. Inbox markers are deliberately
        //    NOT honored here: a live log from a Galaxy S24 showed the
        //    home feed consistently classified DM_INBOX because the
        //    action bar's inbox-button id matches the "inbox_"
        //    substring. The real inbox never shows the bottom nav, so
        //    it is classified by the fullscreen branch below.
        val selectedTab = extractSelectedBottomTabFromNodes(nodes)
        if (selectedTab != null && selectedTab != "reels") {
            // Story viewer wins over the thread-marker check: the story
            // reply box ("Send message") is exactly what the composer
            // fallback matches.
            if (hasStoryViewerMarkers(nodes)) return ScreenType.OTHER
            if (hasDmThreadMarkers(nodes)) return ScreenType.DM_THREAD
            return ScreenType.OTHER
        }

        // 2. Reels tab is the *only* selected bottom-nav item — block.
        if (selectedTab == "reels") return ScreenType.REELS_TAB

        // 3. contentDescription-based bottom-nav check above failed
        //    (obfuscated build or missing a11y labels) but the clips_tab
        //    id is explicitly selected — still a REELS_TAB.
        if (nodes.any { node ->
                node.isSelected &&
                    node.viewIdResourceName?.lowercase()?.contains("clips_tab") == true
            }
        ) return ScreenType.REELS_TAB

        // 3b. Tab bar in the tree but NO tab reports selected — Samsung
        //     leaves the selection state unpopulated for 300–700 ms after
        //     a tab switch. Whatever is on screen, it is a main-app
        //     surface with the bottom nav visible, NOT a fullscreen
        //     viewer — so don't fall through to the reel-player
        //     heuristics: the home feed carries 3 action descriptions
        //     plus a video SurfaceView and would false-positive as
        //     REEL_VIEWER. Clips-viewer ids already mounted mean the
        //     Reels tab is what's loading; everything else is OTHER.
        if (nodesHaveMainTabBar(nodes)) {
            return when {
                countReelViewerIdMarkers(nodes) > 0 -> ScreenType.REELS_TAB
                hasStoryViewerMarkers(nodes) -> ScreenType.OTHER
                hasDmThreadMarkers(nodes) -> ScreenType.DM_THREAD
                else -> ScreenType.OTHER
            }
        }

        // 4. No bottom nav visible — fullscreen flow (reel viewer, DM
        //    thread overlay, story viewer, modal). Now reel-viewer
        //    markers are meaningful.
        val reelViewerIdHits = countReelViewerIdMarkers(nodes)
        val reelActionHits = countReelActionDescriptions(nodes)
        val hasVideo = hasVideoSurface(nodes)

        // REEL-specific hints: audio / original audio / remix / reel
        // options / mute / unmute are icons regular feed posts never
        // carry, so even a single one is a strong positive signal.
        // The "4+ action icons" branch is a secondary belt-and-braces
        // path for releases that rename the video surface to a class
        // we don't recognise — a regular fullscreen post tops out at
        // 3 icons (like, comment, share) so the gap at 4 is safe.
        // clips_* ids are authoritative for the actual reel player and
        // are checked before the story exclusion; the fuzzy action-icon
        // heuristics run after it, because a fullscreen STORY also
        // carries like/share/mute icons plus a video surface and would
        // otherwise false-positive as REEL_VIEWER.
        return when {
            reelViewerIdHits > 0 -> ScreenType.REEL_VIEWER
            hasStoryViewerMarkers(nodes) -> ScreenType.OTHER
            (reelActionHits >= 3 && hasVideo) || reelActionHits >= 4 ->
                ScreenType.REEL_VIEWER
            hasDmThreadMarkers(nodes) -> ScreenType.DM_THREAD
            hasDmInboxMarkers(nodes) -> ScreenType.DM_INBOX
            else -> ScreenType.OTHER
        }
    }

    /**
     * Short, human-readable summary of what the detector "saw" in a
     * snapshot. Surfaced in the live log so the user can tell at a
     * glance whether Instagram changed resource ids out from under us.
     */
    fun diagnosticSummary(root: NodeSnapshot?): String {
        if (root == null) return "root=null"
        val debug = inspectTree(root)
        if (debug == null) return "root=null"
        return buildString {
            append("nodes=")
            append(debug.nodeCount)
            append(" depth=")
            append(debug.maxDepth)
            if (debug.interestingIds.isNotEmpty()) {
                append(" ids=")
                append(debug.interestingIds.joinToString(","))
            } else if (debug.sampledIds.isNotEmpty()) {
                append(" sample=")
                append(debug.sampledIds.joinToString(","))
            }
            if (debug.sampledDescriptions.isNotEmpty()) {
                if (isNotEmpty()) append(" ")
                append("desc=")
                append(debug.sampledDescriptions.joinToString(","))
            }
        }
    }

    fun inspectTree(root: NodeSnapshot?): TreeDebug? {
        if (root == null) return null
        val nodes = root.flatten().toList()
        // Classification-relevant ids (clips/reel/DM) sort ahead of
        // generic ones so the 12-item cap can't hide the id that caused
        // a DM/REEL classification — a Galaxy S24 log once needed the
        // inbox-button id to diagnose a misclassification and it had
        // been truncated out behind a dozen row_feed_* ids.
        val interestingIds = nodes
            .mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }
            .filter { id ->
                val l = id.lowercase()
                l.contains("clip") || l.contains("reel") ||
                    l.contains("direct") || l.contains("thread") ||
                    l.contains("message") || l.contains("tab_") ||
                    l.contains("composer") || l.contains("inbox") ||
                    l.contains("feed")
            }
            .distinct()
            .sortedBy { id ->
                val l = id.lowercase()
                val decisive = l.contains("clip") || l.contains("reel") ||
                    l.contains("direct") || l.contains("thread") ||
                    l.contains("inbox")
                if (decisive) 0 else 1
            }
            .take(12)
            .toList()
        val sampledIds = nodes
            .mapNotNull { it.viewIdResourceName?.substringAfterLast('/') }
            .distinct()
            .take(8)
            .toList()
        val sampledDescriptions = nodes
            .mapNotNull { it.contentDescription?.trim()?.takeIf(String::isNotEmpty) }
            .distinct()
            .take(8)
            .toList()
        return TreeDebug(
            nodeCount = nodes.size,
            maxDepth = treeDepth(root),
            interestingIds = interestingIds,
            sampledIds = sampledIds,
            sampledDescriptions = sampledDescriptions,
        )
    }

    /**
     * Lightweight fingerprint of the current reel used to distinguish
     * "still watching the same reel" from "user swiped to a new one".
     *
     * Username-only by design: the previous implementation mixed in a
     * hash of caption/audio/title text, which drifted as those fields
     * loaded frame-by-frame after the reel opened — so a legitimately
     * DM-opened reel would produce a different signature a beat later
     * and trip the "signature changed, user must have swiped" branch
     * in [SessionStateMachine], blocking a reel we had just allowed.
     *
     * Username is stable from the moment the reel layout lays out (IG
     * fills it in as soon as the overlay inflates) and every reel has
     * exactly one, so it is sufficient: a swipe to a reel by a
     * different author produces a different signature, and a swipe to
     * a different reel by the same author is rare enough in the
     * DM-shared flow to accept as a false-negative.
     */
    fun extractReelSignature(root: NodeSnapshot?): String? {
        if (root == null) return null
        val nodes = root.flatten().toList()

        // clips-scoped only: a transitional tree (reel mounting over a DM
        // thread or the feed) carries OTHER surfaces' profile_name /
        // username nodes — a field log showed an ad's "squarespace" and a
        // feed collab line latched as the reel author, which then made
        // the legit reel look like a swipe. Only the clips viewer's own
        // author node is trustworthy.
        val username = nodes.firstOrNull { node ->
            val id = node.viewIdResourceName?.lowercase() ?: return@firstOrNull false
            val hasUsernameId = id.contains("clips") &&
                (id.contains("profile_name") || id.contains("username") || id.contains("profile"))
            hasUsernameId && !node.text.isNullOrBlank()
        }?.text?.trim()

        if (!username.isNullOrBlank()) {
            return SIGNATURE_USER_PREFIX + username
        }

        // Fallback: no username exposed in the tree yet — hash the
        // stablest small piece of text we can find so the state
        // machine at least has a coarse fingerprint to compare against
        // once the username does appear. Still limited to short
        // strings so the fingerprint doesn't drift on captions.
        val candidates = nodes
            .mapNotNull { it.text?.toString()?.trim().takeIf { s -> !s.isNullOrBlank() } }
            .filter { it.length in 1..60 }
            .take(3)
            .toList()
        return if (candidates.isNotEmpty()) {
            "txt:" + candidates.joinToString("|").hashCode().toString()
        } else null
    }

    // ---- signal helpers ----

    /**
     * True when the Instagram bottom tab bar is anywhere in the given
     * tree — a strict signal that the user is on a main-app surface
     * (Home / Search / Reels / Profile / Notifications) and not in a
     * DM thread overlay or fullscreen reel viewer. Used by the service
     * as a fallback "am I on a stable non-DM surface?" check when
     * Samsung's accessibility tree fails to report isSelected on any
     * tab button during the first 300–700 ms after a screen change.
     */
    fun treeHasMainTabBar(root: NodeSnapshot?): Boolean {
        if (root == null) return false
        return nodesHaveMainTabBar(root.flatten().toList())
    }

    private fun nodesHaveMainTabBar(nodes: List<NodeSnapshot>): Boolean {
        return nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: return@any false
            id.contains("main_tab_bar") || id.contains("tab_bar_container")
        }
    }

    fun extractSelectedBottomTab(root: NodeSnapshot?): String? {
        if (root == null) return null
        return extractSelectedBottomTabFromNodes(root.flatten().toList())
    }

    /**
     * Walks the tree looking for bottom-nav tab buttons that report
     * `isSelected=true`. Returns the first non-Reels tab if one is
     * selected, only returning "reels" when nothing else is. This
     * preference matters because Samsung's accessibility tree can
     * report BOTH the previously-selected and newly-selected tabs as
     * `isSelected=true` during the 300–700 ms window after a tap,
     * which naive first-match logic classified as REELS_TAB whenever
     * Reels happened to be traversed first — causing legitimate taps
     * on Search/Home/Profile to trigger the Reels-block routine.
     */
    private fun extractSelectedBottomTabFromNodes(nodes: List<NodeSnapshot>): String? {
        var reelsSeen = false
        for (node in nodes) {
            if (!node.isSelected) continue
            val desc = node.contentDescription?.trim()?.lowercase() ?: continue
            val tab = when {
                desc == "home" || desc.startsWith("home,") || desc.startsWith("home ") || desc.startsWith("home tab") -> "home"
                desc == "search" || desc.startsWith("search,") || desc.startsWith("search ") || desc.startsWith("search tab") -> "search"
                desc == "reels" || desc.startsWith("reels,") || desc.startsWith("reels ") || desc.startsWith("reels tab") -> "reels"
                desc == "profile" || desc.startsWith("profile,") || desc.startsWith("profile ") || desc.startsWith("profile tab") -> "profile"
                desc == "notifications" || desc.startsWith("notifications,") || desc.startsWith("notifications ") || desc.startsWith("notifications tab") -> "notifications"
                else -> null
            }
            if (tab == "reels") {
                reelsSeen = true
            } else if (tab != null) {
                return tab
            }
        }
        return if (reelsSeen) "reels" else null
    }

    private fun countReelViewerIdMarkers(nodes: List<NodeSnapshot>): Int {
        return nodes.count { node ->
            val seg = node.viewIdResourceName?.lowercase()?.substringAfterLast('/')
                ?: return@count false
            seg in REEL_VIEWER_ID_SEGMENTS || seg.startsWith(REEL_VIEWER_ID_PREFIX)
        }
    }

    private fun countReelActionDescriptions(nodes: List<NodeSnapshot>): Int {
        val seen = mutableSetOf<String>()
        for (node in nodes) {
            val desc = node.contentDescription?.lowercase() ?: continue
            for (hint in REEL_ACTION_DESC_HINTS) {
                if (desc.contains(hint)) {
                    seen.add(hint)
                    break
                }
            }
        }
        return seen.size
    }

    private fun hasVideoSurface(nodes: List<NodeSnapshot>): Boolean {
        return nodes.any { node ->
            val cls = node.className ?: return@any false
            VIDEO_CLASS_HINTS.any { hint -> cls.contains(hint, ignoreCase = true) }
        }
    }

    private fun hasStoryViewerMarkers(nodes: List<NodeSnapshot>): Boolean {
        return nodes.any { node ->
            val seg = node.viewIdResourceName?.lowercase()?.substringAfterLast('/')
                ?: return@any false
            STORY_VIEWER_ID_PREFIXES.any { prefix -> seg.startsWith(prefix) }
        }
    }

    private fun hasDmThreadMarkers(nodes: List<NodeSnapshot>): Boolean {
        val idHit = nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: return@any false
            DM_THREAD_ID_SUBSTRINGS.any { sub -> id.contains(sub) }
        }
        if (idHit) return true
        // Fallback: an editable message composer with a "Message…" hint.
        return nodes.any { node ->
            val cls = node.className ?: return@any false
            if (!cls.contains("EditText", ignoreCase = true)) return@any false
            val desc = node.contentDescription?.lowercase().orEmpty()
            val text = node.text?.lowercase().orEmpty()
            desc.contains("message") || text.contains("message") ||
                desc.contains("type") || text.contains("type a message")
        }
    }

    private fun hasDmInboxMarkers(nodes: List<NodeSnapshot>): Boolean {
        return nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase() ?: return@any false
            DM_INBOX_ID_SUBSTRINGS.any { sub -> id.contains(sub) }
        }
    }

    private fun treeDepth(root: NodeSnapshot): Int {
        if (root.children.isEmpty()) return 1
        return 1 + root.children.maxOf { child -> treeDepth(child) }
    }
}

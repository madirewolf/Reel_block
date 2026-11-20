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

    // Stable labels for the bottom-nav tabs, used by the service to
    // remember where to send the user back to.
    const val TAB_HOME = "home"
    const val TAB_SEARCH = "search"
    const val TAB_REELS = "reels"
    const val TAB_DIRECT = "direct"
    const val TAB_PROFILE = "profile"
    const val TAB_NOTIFICATIONS = "notifications"

    /** Ids of the bottom navigation bar. Instagram 457 calls it plain
     *  `tab_bar`; older builds used `main_tab_bar` / `tab_bar_container`.
     *  Matched on the id segment so `tab_bar_shadow` doesn't count. */
    private val TAB_BAR_ID_SEGMENTS = setOf("tab_bar", "main_tab_bar", "tab_bar_container")

    /** Bottom-nav tab button ids (Instagram 457). Locale-independent,
     *  unlike the content descriptions. */
    private val TAB_ID_SEGMENTS = mapOf(
        "feed_tab" to TAB_HOME,
        "search_tab" to TAB_SEARCH,
        "clips_tab" to TAB_REELS,
        "direct_tab" to TAB_DIRECT,
        "profile_tab" to TAB_PROFILE,
        "news_tab" to TAB_NOTIFICATIONS,
    )

    fun isTabBarId(viewId: String?): Boolean {
        val seg = viewId?.lowercase()?.substringAfterLast('/') ?: return false
        return seg in TAB_BAR_ID_SEGMENTS
    }

    /**
     * Home-feed post ids, Instagram 457. A post is a header
     * (`row_feed_profile_header`, "x posted a video 3 days ago") above or
     * over its media box (`media_group`). Before a reel plays, the box
     * holds a thumbnail (`row_feed_photo_imageview`, "Reel by x"); while
     * it plays the box holds only the video surface; when it ends a
     * "Watch more reels" button appears in it.
     */
    const val FEED_MEDIA_GROUP_ID = "media_group"
    const val FEED_MEDIA_IMAGE_ID = "row_feed_photo_imageview"
    const val FEED_HEADER_ID = "row_feed_profile_header"
    const val FEED_REEL_CTA_ID = "feed_preview_keep_watching_button"

    private val FEED_REEL_DESCRIPTION =
        Regex("""^(?:\p{L}+\s+)?(?:reel|video) by\b""", RegexOption.IGNORE_CASE)
    private val FEED_POSTED_HEADER = Regex("""\bposted an? \w+""", RegexOption.IGNORE_CASE)
    private val FEED_VIDEO_HEADER = Regex("""\bposted an? (?:video|reel)\b""", RegexOption.IGNORE_CASE)

    /** True when a feed thumbnail description names a reel (or a video,
     *  which Instagram now plays as a reel): "Reel by x", "Sponsored Reel by x". */
    fun isFeedReelDescription(desc: CharSequence?): Boolean =
        desc != null && FEED_REEL_DESCRIPTION.containsMatchIn(desc.trim())

    enum class FeedNodeKind { MEDIA_GROUP, MEDIA_IMAGE, HEADER, REEL_CTA }

    /** A Home-feed node as the service read it (one id lookup per kind). */
    data class FeedNode(
        val kind: FeedNodeKind,
        val description: String?,
        val rect: ScreenRect,
        val visible: Boolean = true,
    )

    /**
     * Screen areas to cover for the "Hide reels in Home feed" option: the
     * media box of every reel post on screen. A box is a reel when its
     * thumbnail says so, when it shows the end-of-reel button, or — with
     * no thumbnail description in it (a video is playing) — when the
     * post's header says "posted a video". Headers are used even when not
     * visible (see below); everything else must be on screen.
     */
    fun feedReelRects(nodes: List<FeedNode>): List<ScreenRect> {
        val visible = nodes.filter { it.visible && it.rect.width > 0 && it.rect.height > 0 }
        val headers = nodes.filter { it.kind == FeedNodeKind.HEADER }
        fun inside(n: FeedNode, box: ScreenRect): Boolean {
            val cx = (n.rect.left + n.rect.right) / 2
            val cy = (n.rect.top + n.rect.bottom) / 2
            return cx in box.left..box.right && cy in box.top..box.bottom
        }
        return visible.filter { it.kind == FeedNodeKind.MEDIA_GROUP }
            .filter { group ->
                val box = group.rect
                val thumbs = visible.filter {
                    it.kind == FeedNodeKind.MEDIA_IMAGE && !it.description.isNullOrBlank() && inside(it, box)
                }
                when {
                    thumbs.any { isFeedReelDescription(it.description) } -> true
                    thumbs.isNotEmpty() -> false
                    visible.any { it.kind == FeedNodeKind.REEL_CTA && inside(it, box) } -> true
                    else -> {
                        // No described thumbnail: a video is playing (a
                        // photo always keeps its "Photo by" thumbnail).
                        // The post's header ("x posted a video") lies over
                        // the top of the box or just above it, and settles
                        // it when present. Scrolled off screen, it either
                        // stays in the tree (not visible, top clamped to
                        // the list's top edge, which is the box's top
                        // too) or is gone; with no header, trust the
                        // missing thumbnail.
                        val header = headers.firstOrNull {
                            it.rect.top in (box.top - HEADER_REACH_PX)..(box.top + HEADER_SLOP_PX) &&
                                FEED_POSTED_HEADER.containsMatchIn(it.description.orEmpty())
                        }
                        header == null || FEED_VIDEO_HEADER.containsMatchIn(header.description.orEmpty())
                    }
                }
            }
            .map { it.rect }
            .distinct()
    }

    private const val HEADER_SLOP_PX = 24

    /** How far above its media box a post header may start (a header is
     *  ~140 px tall on a 1080 px wide screen). */
    private const val HEADER_REACH_PX = 260

    /** Tab bar id segments, most current first, for direct id lookups. */
    val TAB_BAR_ID_LOOKUP: List<String> = listOf("tab_bar", "main_tab_bar", "tab_bar_container")

    /** The view-id segment of the bottom-nav button for [tabLabel], or null. */
    fun tabViewIdSegment(tabLabel: String): String? =
        TAB_ID_SEGMENTS.entries.firstOrNull { it.value == tabLabel }?.key

    /** Tab label for a bottom-nav button id, or null. */
    fun tabLabelFromViewId(viewId: String?): String? {
        val seg = viewId?.lowercase()?.substringAfterLast('/') ?: return null
        return TAB_ID_SEGMENTS[seg]
    }

    /** Tab label for a bottom-nav button's content description
     *  ("Home", "Reels", "Search and explore", "Message", ...), or null. */
    fun tabLabelFromDescription(desc: CharSequence?): String? {
        val d = desc?.toString()?.trim()?.lowercase() ?: return null
        fun word(w: String) = d == w || d.startsWith("$w,") || d.startsWith("$w ")
        return when {
            word("home") -> TAB_HOME
            word("search") -> TAB_SEARCH
            word("reels") -> TAB_REELS
            word("profile") -> TAB_PROFILE
            word("notifications") -> TAB_NOTIFICATIONS
            word("message") || word("messages") || word("direct") -> TAB_DIRECT
            else -> null
        }
    }

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
     * Long-press preview ("peek") of a grid tile, Instagram 457. Holding a
     * tile on Explore (or a profile grid) opens `peek_container` over the
     * grid and plays the post in it, with a Like/Share/... menu in a
     * separate popup window. The Search tab stays selected underneath,
     * so the preview read as OTHER and the reel played.
     */
    const val PEEK_CONTAINER_ID = "peek_container"

    /** The preview's menu, in its own popup window that has focus. */
    private val PEEK_MENU_ID_SEGMENTS = setOf("context_menu", "context_menu_options_list")

    /** Video player parts inside a preview; a photo preview has none. */
    private val PEEK_VIDEO_ID_SEGMENTS = setOf("video_states", "countdown_timer")

    /** Feed post rows (`row_feed_profile_header`, `row_feed_photo_imageview`, ...). */
    private const val FEED_ROW_ID_PREFIX = "row_feed_"

    /** The reply bar under a reel opened from a DM thread, Instagram 457. */
    private val DM_REPLY_BAR_ID_SEGMENTS = setOf(
        "reel_viewer_message_composer", "reply_bar_container", "reply_bar_edittext",
    )

    /** Profile page ids, Instagram 457, checked against live trees. */
    private val PROFILE_HEADER_ID_SEGMENTS = setOf("profile_header_container", "row_profile_header")
    private const val PROFILE_PAGER_ID = "profile_viewpager"
    /** Only on the user's own profile: the "+" create button and the account-switch chevron. */
    private val OWN_PROFILE_ID_SEGMENTS = setOf(
        "profile_header_create_button", "action_bar_title_chevron", "create_highlight_button_view",
    )
    /** Only on someone else's profile. */
    private val PROFILE_FOLLOW_ID_SEGMENTS = setOf(
        "profile_header_follow_button", "profile_header_user_action_follow_button",
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
        sourceViewId: String? = null,
    ): ScreenType? {
        if (eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != android.view.accessibility.AccessibilityEvent.TYPE_VIEW_SELECTED
        ) {
            return null
        }
        val isClick = eventType == android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED
        val tab = bottomNavTabForEvent(eventContentDescription, sourceViewId, isClick) ?: return null
        return when (tab) {
            TAB_REELS -> ScreenType.REELS_TAB
            TAB_DIRECT -> ScreenType.DM_INBOX
            else -> ScreenType.OTHER
        }
    }

    /**
     * Which bottom-nav tab a click/select event is for, or null when it
     * isn't a bottom-nav tap. A known tab id decides. Any other id means
     * a different control: another user's profile has a "Reels" grid
     * tab, and treating that tap as the Reels tab blocked their profile.
     * Only a click with no id at all falls back to the description: on
     * Instagram 457 opening a Home feed reel fires a select event from an
     * unnamed view described "Reels", and reading that as a Reels tab tap
     * blocked the reel even with Home switched off.
     */
    fun bottomNavTabForEvent(
        eventContentDescription: CharSequence?,
        sourceViewId: String?,
        isClick: Boolean = true,
    ): String? {
        tabLabelFromViewId(sourceViewId)?.let { return it }
        if (sourceViewId != null || !isClick) return null
        return tabLabelFromDescription(eventContentDescription)
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

        // 0. A long-press preview playing a video is a reel on screen,
        //    whatever tab is selected under it.
        if (hasVideoPeek(nodes)) return ScreenType.REEL_VIEWER

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
        if (selectedTab != null && selectedTab != TAB_REELS) {
            // Story viewer wins over the thread-marker check: the story
            // reply box ("Send message") is exactly what the composer
            // fallback matches.
            if (hasStoryViewerMarkers(nodes)) return ScreenType.OTHER
            if (hasDmThreadMarkers(nodes)) return ScreenType.DM_THREAD
            // Instagram 457 made Messages a bottom tab ("direct_tab").
            if (selectedTab == TAB_DIRECT) return ScreenType.DM_INBOX
            return ScreenType.OTHER
        }

        // 2. Reels tab is the *only* selected bottom-nav item — block.
        if (selectedTab == TAB_REELS) return ScreenType.REELS_TAB

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
        // Feed rows veto the fuzzy branch: a read of just the Home feed's
        // list (no tab bar in it) carries like / comment / share icons
        // and a playing video, and blocking it sent Home back to the top
        // on every scroll.
        return when {
            reelViewerIdHits > 0 -> ScreenType.REEL_VIEWER
            hasStoryViewerMarkers(nodes) -> ScreenType.OTHER
            ((reelActionHits >= 3 && hasVideo) || reelActionHits >= 4) &&
                !hasFeedRowMarkers(nodes) ->
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

    private fun nodesHaveMainTabBar(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { isTabBarId(it.viewIdResourceName) }

    fun extractSelectedBottomTab(root: NodeSnapshot?): String? {
        if (root == null) return null
        return extractSelectedBottomTabFromNodes(root.flatten().toList())
    }

    /**
     * Which bottom-nav tab reports `isSelected=true`.
     *
     * Only the bottom bar is searched when it is in the tree: the Reels
     * page's own "Reels | Friends" header and another user's profile
     * "Reels" grid tab are also selected nodes described "Reels", and
     * must not read as the Reels tab. Tab ids win over descriptions.
     * Without a recognisable bar (older builds) the whole tree is
     * searched by description.
     *
     * Returns the first non-Reels tab if one is selected, only returning
     * "reels" when nothing else is. Samsung's accessibility tree can
     * report BOTH the previously-selected and newly-selected tabs as
     * `isSelected=true` for 300–700 ms after a tap; naive first-match
     * logic made taps on Search/Home/Profile trigger the Reels block.
     *
     * Inside the bar, a tab counts as selected when the tab button or
     * anything inside it is: the November 2026 Instagram update marks
     * only the button's unlabelled `tab_icon` child as selected.
     */
    private fun extractSelectedBottomTabFromNodes(nodes: List<NodeSnapshot>): String? {
        val tabBar = nodes.firstOrNull { isTabBarId(it.viewIdResourceName) }
        var reelsSeen = false
        for (node in tabBar?.flatten() ?: nodes.asSequence()) {
            val byId = tabLabelFromViewId(node.viewIdResourceName)
            // Outside a tab bar, "Reels" by label alone is not the tab:
            // a reel opened from the Home feed plays full screen under a
            // selected "Reels" header, and reading that as the Reels tab
            // ignored the Home switch.
            val byLabel = tabLabelFromDescription(node.contentDescription)
                ?.takeIf { tabBar != null || it != TAB_REELS }
            val tab = byId ?: byLabel ?: continue
            val selected = if (tabBar != null) node.flatten().any { it.isSelected } else node.isSelected
            if (!selected) continue
            if (tab == TAB_REELS) reelsSeen = true else return tab
        }
        return if (reelsSeen) TAB_REELS else null
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

    private fun idSegment(node: NodeSnapshot): String? =
        node.viewIdResourceName?.lowercase()?.substringAfterLast('/')

    /** Home-feed post rows: `row_feed_*` ids or the post's media group. */
    private fun hasFeedRowMarkers(nodes: List<NodeSnapshot>): Boolean =
        nodes.any { node ->
            val seg = idSegment(node) ?: return@any false
            seg.startsWith(FEED_ROW_ID_PREFIX) || seg == FEED_MEDIA_GROUP_ID
        }

    /** True when the real reel player's own `clips_viewer*` ids are in [root]. */
    fun hasReelViewerIds(root: NodeSnapshot?): Boolean =
        root != null && countReelViewerIdMarkers(root.flatten().toList()) > 0

    /**
     * Reel signs that hold up in a read of one scrolled list on its own:
     * the player's own ids, or a long-press preview playing a video. The
     * fuzzy action-icon count does not: a Home feed list read alone has
     * no tab bar around it and can look like a player.
     */
    fun hasStrongReelMarkers(root: NodeSnapshot?): Boolean {
        if (root == null) return false
        val nodes = root.flatten().toList()
        return countReelViewerIdMarkers(nodes) > 0 || hasVideoPeek(nodes)
    }

    /**
     * True when the reel player in [root] has the "Send message" reply
     * bar under it. Instagram 457 draws it only on a reel opened from a
     * DM thread, so it marks a DM reel even before the thread was read.
     */
    fun hasDmReplyBar(root: NodeSnapshot?): Boolean =
        root != null && root.flatten().any { idSegment(it) in DM_REPLY_BAR_ID_SEGMENTS }

    /** What kind of profile page, if any, [root] shows. */
    enum class ProfilePageKind {
        /** The user's own profile, header on screen. */
        OWN,
        /** Someone else's profile, header on screen. */
        OTHER_PROFILE,
        /** A profile's grid scrolled past its header: whose is unknown. */
        NO_HEADER,
        /** A main tab with no profile page on it (Home, Explore, Reels, DMs). */
        MAIN_SURFACE,
        /** Anything else, or too little of the tree to tell. */
        NEUTRAL,
    }

    fun profilePageKind(root: NodeSnapshot?): ProfilePageKind {
        if (root == null) return ProfilePageKind.NEUTRAL
        val nodes = root.flatten().toList()
        val segs = nodes.mapNotNull { idSegment(it) }.toSet()
        val hasHeader = PROFILE_HEADER_ID_SEGMENTS.any { it in segs }
        if (hasHeader) {
            val followButton = PROFILE_FOLLOW_ID_SEGMENTS.any { it in segs }
            val ownMarker = OWN_PROFILE_ID_SEGMENTS.any { it in segs } ||
                nodes.any { n ->
                    val d = n.contentDescription?.trim()?.lowercase() ?: return@any false
                    d == "edit profile" || d.startsWith("your profile")
                }
            return if (ownMarker && !followButton) ProfilePageKind.OWN else ProfilePageKind.OTHER_PROFILE
        }
        if (PROFILE_PAGER_ID in segs) return ProfilePageKind.NO_HEADER
        return if (nodesHaveMainTabBar(nodes)) ProfilePageKind.MAIN_SURFACE else ProfilePageKind.NEUTRAL
    }

    /** True when [nodes] hold a long-press preview that plays a video:
     *  a video surface or player ids in it, or a "posted a video" header. */
    private fun hasVideoPeek(nodes: List<NodeSnapshot>): Boolean =
        nodes.filter { idSegment(it) == PEEK_CONTAINER_ID }.any { peek ->
            val inside = peek.flatten().toList()
            hasVideoSurface(inside) ||
                inside.any { idSegment(it) in PEEK_VIDEO_ID_SEGMENTS } ||
                inside.any {
                    idSegment(it) == FEED_HEADER_ID &&
                        FEED_VIDEO_HEADER.containsMatchIn(it.contentDescription.orEmpty())
                }
        }

    /** True when [root] is the menu window of a long-press preview; the
     *  preview itself is then in Instagram's main window, behind it. */
    fun isPeekMenu(root: NodeSnapshot?): Boolean =
        root != null && root.flatten().any { idSegment(it) in PEEK_MENU_ID_SEGMENTS }

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

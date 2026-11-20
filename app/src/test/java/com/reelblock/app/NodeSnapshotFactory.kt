package com.reelblock.app

/**
 * Builders that produce NodeSnapshot trees shaped like the ones Reel
 * Block actually sees from Instagram. Using builders instead of raw
 * constructors keeps the tests declarative and lets us tweak the
 * "shape" of a screen in one place if Instagram's hierarchy drifts.
 */
object NodeSnapshotFactory {

    fun node(
        id: String? = null,
        contentDescription: String? = null,
        text: String? = null,
        className: String? = null,
        isSelected: Boolean = false,
        isScrollable: Boolean = false,
        isVisibleToUser: Boolean = true,
        children: List<NodeSnapshot> = emptyList(),
    ): NodeSnapshot = NodeSnapshot(
        viewIdResourceName = id,
        contentDescription = contentDescription,
        text = text,
        className = className,
        isSelected = isSelected,
        isScrollable = isScrollable,
        children = children,
        isVisibleToUser = isVisibleToUser,
    )

    // ---- Instagram 457 (Nov 2026) shapes, checked against a live tree ----

    private const val IG = "com.instagram.android:id/"

    /** The 457 bottom bar: `tab_bar` holding one id'd button per tab. */
    fun ig457TabBar(selected: String): NodeSnapshot = node(
        id = IG + "tab_bar",
        children = listOf(
            "feed_tab" to "Home",
            "clips_tab" to "Reels",
            "direct_tab" to "Message",
            "search_tab" to "Search and explore",
            "profile_tab" to "Profile",
        ).map { (id, desc) ->
            node(
                id = IG + id,
                contentDescription = desc,
                isSelected = ReelDetector.tabLabelFromViewId(IG + id) == selected,
            )
        },
    )

    /**
     * A 457 main screen. Every tab's page stays mounted in
     * `swipeable_tab_view_pager`; only the current one is visible. The
     * hidden Reels page carries real clips ids and a reel author, which
     * must never leak into detection of the visible page.
     */
    fun ig457MainScreen(selected: String): NodeSnapshot {
        val homePage = node(
            id = IG + "feed_page",
            isVisibleToUser = selected == ReelDetector.TAB_HOME,
            children = listOf(
                node(id = "android:id/list", isScrollable = true, children = listOf(
                    node(id = IG + "row_feed_profile_header", contentDescription = "some.author posted a photo 2 days ago"),
                    node(id = IG + "row_feed_photo_imageview", contentDescription = "Photo by Some Author"),
                    node(contentDescription = "Like"),
                    node(contentDescription = "Comment"),
                )),
            ),
        )
        val reelsPage = node(
            id = IG + "clips_page",
            isVisibleToUser = selected == ReelDetector.TAB_REELS,
            children = listOf(
                node(id = IG + "clips_viewer_view_pager", isScrollable = true, children = listOf(
                    node(id = IG + "clips_item_profile_name", text = "hidden.reel.author"),
                )),
                // The Reels page's own header: a selected "Reels" tab
                // that is not the bottom-bar button.
                node(id = IG + "clips_tab_header_reels", contentDescription = "Reels", isSelected = true),
            ),
        )
        val inboxPage = node(
            id = IG + "direct_page",
            isVisibleToUser = selected == ReelDetector.TAB_DIRECT,
            children = listOf(
                node(id = IG + "thread_list", isScrollable = true, children = listOf(
                    node(text = "a friend"),
                )),
            ),
        )
        return node(
            id = IG + "main_container",
            children = listOf(
                node(
                    id = IG + "swipeable_tab_view_pager",
                    isScrollable = true,
                    children = listOf(homePage, reelsPage, inboxPage),
                ),
                ig457TabBar(selected),
            ),
        )
    }

    /**
     * Instagram 457's long-press preview of an Explore tile, as the main
     * window reports it: the grid with the Search tab selected, and a
     * `peek_container` over it playing the post. Checked against a live
     * Galaxy S24 tree.
     */
    fun ig457ExplorePeek(video: Boolean): NodeSnapshot {
        val media = if (video) {
            node(id = IG + "media_group", children = listOf(
                node(className = "android.view.TextureView"),
                node(id = IG + "row_feed_media_actions", children = listOf(
                    node(id = IG + "video_states", children = listOf(
                        node(id = IG + "countdown_timer", className = "android.widget.TextView"),
                    )),
                )),
                node(id = IG + "indicator_container", contentDescription = "Turn sound on"),
            ))
        } else {
            node(id = IG + "media_group", children = listOf(
                node(id = IG + "row_feed_photo_imageview", className = "android.widget.ImageView"),
            ))
        }
        val peek = node(
            id = IG + "peek_container",
            children = listOf(
                node(id = IG + "media_container", children = listOf(
                    node(
                        id = IG + "row_feed_profile_header",
                        contentDescription = if (video) "some.author posted a video" else "some.author posted a photo",
                    ),
                    media,
                )),
                node(id = IG + "like_heart", className = "android.widget.ImageView"),
            ),
        )
        val grid = node(id = "android:id/list", isScrollable = true, children = listOf(
            node(id = IG + "grid_card_layout_container", contentDescription = "Reel by some.author at row 2, column 2"),
            node(id = IG + "grid_card_layout_container", contentDescription = "Photo by other.author at row 2, column 3"),
        ))
        return node(
            id = IG + "main_container",
            children = listOf(grid, peek, ig457TabBar(ReelDetector.TAB_SEARCH)),
        )
    }

    /** The preview's menu: a separate popup window that has focus. */
    fun ig457PeekMenu(): NodeSnapshot = node(
        className = "android.widget.FrameLayout",
        children = listOf(
            node(id = IG + "context_menu", children = listOf(
                node(id = IG + "context_menu_options_list", children = listOf(
                    "Like", "Share", "View profile", "Repost", "Not interested", "Report",
                ).map { node(id = IG + "context_menu_item", contentDescription = it) }),
            )),
        ),
    )

    // ---- Prefabs that look like real Instagram screens ----

    fun reelsTabScreen(reelAuthor: String = "first.author"): NodeSnapshot = node(
        id = "com.instagram.android:id/root_view",
        children = listOf(
            node(
                id = "com.instagram.android:id/clips_viewer_view_pager",
                isScrollable = true,
                children = listOf(
                    node(
                        id = "com.instagram.android:id/clips_item_profile_name",
                        text = reelAuthor,
                    ),
                    node(
                        id = "com.instagram.android:id/clips_caption",
                        text = "on a hike",
                    ),
                ),
            ),
            node(
                id = "com.instagram.android:id/main_tab_bar",
                children = listOf(
                    node(contentDescription = "Home", isSelected = false),
                    node(contentDescription = "Search", isSelected = false),
                    node(contentDescription = "Reels", isSelected = true),
                    node(contentDescription = "Profile", isSelected = false),
                ),
            ),
        ),
    )

    fun dmThreadScreen(): NodeSnapshot = node(
        id = "com.instagram.android:id/root_view",
        children = listOf(
            node(id = "com.instagram.android:id/direct_thread_toolbar"),
            node(id = "com.instagram.android:id/message_composer", className = "android.widget.EditText"),
            node(
                id = "com.instagram.android:id/thread_view_row_container",
                children = listOf(
                    node(text = "hey check this out", className = "android.widget.TextView"),
                ),
            ),
        ),
    )

    fun dmInboxScreen(): NodeSnapshot = node(
        id = "com.instagram.android:id/root_view",
        children = listOf(
            node(id = "com.instagram.android:id/direct_inbox_container"),
            node(
                id = "com.instagram.android:id/thread_list",
                isScrollable = true,
                children = listOf(node(text = "mohammad"), node(text = "a friend")),
            ),
        ),
    )

    /**
     * Reel viewer as reached from a DM: no bottom-tab bar, reel pager
     * on top, usually with a back arrow toolbar (not modelled here
     * because we don't rely on it).
     */
    fun dmLaunchedReelScreen(author: String): NodeSnapshot = node(
        id = "com.instagram.android:id/root_view",
        children = listOf(
            node(
                id = "com.instagram.android:id/clips_viewer_view_pager",
                isScrollable = true,
                children = listOf(
                    node(
                        id = "com.instagram.android:id/clips_item_profile_name",
                        text = author,
                    ),
                    node(id = "com.instagram.android:id/clips_caption", text = "caption $author"),
                ),
            ),
        ),
    )

    /**
     * Home feed the way a Galaxy S24 actually reports it: the action
     * bar carries a DM-inbox button whose id matches the "inbox_"
     * substring, and the feed rows carry Like/Comment/Save descriptions
     * plus a video SurfaceView — both of which historically caused
     * misclassification (DM_INBOX and REEL_VIEWER respectively).
     */
    fun homeFeedScreen(homeTabSelected: Boolean = true): NodeSnapshot = node(
        id = "com.instagram.android:id/main_container",
        children = listOf(
            node(
                id = "com.instagram.android:id/action_bar_inbox_textview",
                contentDescription = "Direct messages",
            ),
            node(
                id = "com.instagram.android:id/swipeable_tab_view_pager",
                isScrollable = true,
                children = listOf(
                    node(className = "android.view.SurfaceView"),
                    node(contentDescription = "Like"),
                    node(contentDescription = "Comment"),
                    node(contentDescription = "Add to Saved"),
                    node(id = "com.instagram.android:id/row_feed_photo_profile_name", text = "some.author"),
                ),
            ),
            node(
                id = "com.instagram.android:id/main_tab_bar",
                children = listOf(
                    node(contentDescription = "Home", isSelected = homeTabSelected),
                    node(contentDescription = "Search", isSelected = false),
                    node(contentDescription = "Reels", isSelected = false),
                    node(contentDescription = "Profile", isSelected = false),
                ),
            ),
        ),
    )

    fun loadingScreen(): NodeSnapshot = node(
        id = "com.instagram.android:id/placeholder",
        children = listOf(node(className = "android.widget.ProgressBar")),
    )

    /**
     * The STORY viewer, shaped after a Galaxy S24 field log. Instagram
     * calls stories "reel_*" internally (actual Reels are "clips_*").
     * Note the reply box: an EditText whose hint says "Send message" —
     * exactly what the DM-composer fallback matches — plus like/share
     * icons and a video surface that could trip the reel heuristics.
     */
    fun storyViewerScreen(withTabBar: Boolean = false): NodeSnapshot = node(
        id = "com.instagram.android:id/root_view",
        children = buildList {
            add(
                node(
                    id = "com.instagram.android:id/reel_viewer_root",
                    children = listOf(
                        node(id = "com.instagram.android:id/reel_viewer_media_layout", className = "android.view.SurfaceView"),
                        node(id = "com.instagram.android:id/reel_item_toolbar_container"),
                        node(id = "com.instagram.android:id/reel_sticker_overlay_container"),
                        node(
                            className = "android.widget.EditText",
                            contentDescription = "Send message",
                        ),
                        node(contentDescription = "Like"),
                        node(contentDescription = "Share"),
                    ),
                ),
            )
            if (withTabBar) {
                add(
                    node(
                        id = "com.instagram.android:id/main_tab_bar",
                        children = listOf(
                            node(contentDescription = "Home", isSelected = true),
                            node(contentDescription = "Reels", isSelected = false),
                        ),
                    ),
                )
            }
        },
    )
}

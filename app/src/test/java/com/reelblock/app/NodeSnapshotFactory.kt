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
        children: List<NodeSnapshot> = emptyList(),
    ): NodeSnapshot = NodeSnapshot(
        viewIdResourceName = id,
        contentDescription = contentDescription,
        text = text,
        className = className,
        isSelected = isSelected,
        isScrollable = isScrollable,
        children = children,
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

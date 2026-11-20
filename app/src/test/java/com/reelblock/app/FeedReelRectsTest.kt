package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import com.reelblock.app.ReelDetector.FeedNode
import com.reelblock.app.ReelDetector.FeedNodeKind
import org.junit.Test

/**
 * "Hide reels in Home feed": which feed boxes get covered. Shapes and
 * bounds follow a live Instagram 457 feed on a 1080x2340 screen (list
 * spans y 95..2175). Names are made up.
 */
class FeedReelRectsTest {

    private fun box(top: Int, bottom: Int, visible: Boolean = true) =
        FeedNode(FeedNodeKind.MEDIA_GROUP, null, ScreenRect(0, top, 1080, bottom), visible)

    private fun thumb(desc: String, top: Int, bottom: Int) =
        FeedNode(FeedNodeKind.MEDIA_IMAGE, desc, ScreenRect(0, top, 1080, bottom))

    private fun header(desc: String, top: Int, bottom: Int, visible: Boolean = true) =
        FeedNode(FeedNodeKind.HEADER, desc, ScreenRect(0, top, 1080, bottom), visible)

    private fun cta(top: Int) =
        FeedNode(FeedNodeKind.REEL_CTA, "Tap to watch more reels", ScreenRect(307, top, 773, top + 126))

    @Test
    fun `reel thumbnail descriptions`() {
        assertThat(ReelDetector.isFeedReelDescription("Reel by Some Author, Liked by x")).isTrue()
        assertThat(ReelDetector.isFeedReelDescription("Suggested Reel by Some Author")).isTrue()
        assertThat(ReelDetector.isFeedReelDescription("Sponsored Reel by Brand")).isTrue()
        assertThat(ReelDetector.isFeedReelDescription("Video by Some Author")).isTrue()
        assertThat(ReelDetector.isFeedReelDescription("Photo by Some Author")).isFalse()
        assertThat(ReelDetector.isFeedReelDescription("Sponsored Photo by Brand")).isFalse()
        assertThat(ReelDetector.isFeedReelDescription("Reelby store")).isFalse()
        assertThat(ReelDetector.isFeedReelDescription(null)).isFalse()
    }

    @Test
    fun `reel with its thumbnail is covered`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(1942, 2175),
            thumb("Reel by Jo Doe, Liked by a.friend and others", 1942, 2175),
            header("jo.doe posted a video 3 days ago", 1942, 2079),
        ))
        assertThat(rects).containsExactly(ScreenRect(0, 1942, 1080, 2175))
    }

    @Test
    fun `photo is not covered`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(409, 1489),
            thumb("Photo by Jo Doe", 409, 1489),
            header("jo.doe posted a photo November 2", 272, 409),
        ))
        assertThat(rects).isEmpty()
    }

    @Test
    fun `photo ad is not covered`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(95, 646),
            thumb("Sponsored Photo by Brand", 95, 646),
        ))
        assertThat(rects).isEmpty()
    }

    @Test
    fun `playing reel without a thumbnail is covered by its header`() {
        // Bug: the "Reel by" thumbnail is removed once inline playback
        // starts, so a playing reel was left visible.
        val rects = ReelDetector.feedReelRects(listOf(
            box(272, 2084),
            header("jo.doe posted a video November 7", 272, 409),
        ))
        assertThat(rects).containsExactly(ScreenRect(0, 272, 1080, 2084))
    }

    @Test
    fun `finished reel with the watch more button is covered`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(272, 2084),
            cta(1115),
        ))
        assertThat(rects).containsExactly(ScreenRect(0, 272, 1080, 2084))
    }

    @Test
    fun `playing reel whose header scrolled off is still covered`() {
        // Off screen, the header stays in the tree, not visible, with its
        // top clamped to the list's top and its bottom above it.
        val rects = ReelDetector.feedReelRects(listOf(
            box(95, 1536),
            header("jo.doe posted a video October 16", 95, -139, visible = false),
        ))
        assertThat(rects).containsExactly(ScreenRect(0, 95, 1080, 1536))
    }

    @Test
    fun `playing video ad with no header at all is covered`() {
        val rects = ReelDetector.feedReelRects(listOf(box(95, 1159)))
        assertThat(rects).containsExactly(ScreenRect(0, 95, 1080, 1159))
    }

    @Test
    fun `box without thumbnail under a photo header is not covered`() {
        // A photo whose thumbnail description has not loaded yet.
        val rects = ReelDetector.feedReelRects(listOf(
            box(409, 1489),
            header("jo.doe posted a photo November 2", 272, 409),
        ))
        assertThat(rects).isEmpty()
    }

    @Test
    fun `another post's header far above does not decide`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(1400, 2175),
            header("someone.else posted a photo May 1", 95, 230),
        ))
        assertThat(rects).containsExactly(ScreenRect(0, 1400, 1080, 2175))
    }

    @Test
    fun `boxes off screen are ignored`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(2175, 2175, visible = false),
            thumb("Reel by Jo Doe", 2175, 2175),
        ))
        assertThat(rects).isEmpty()
    }

    @Test
    fun `two reels on screen get two covers`() {
        val rects = ReelDetector.feedReelRects(listOf(
            box(95, 1159),
            box(1698, 2175),
            thumb("Suggested Reel by Brand", 1698, 2175),
            header("brand posted a video November 7", 1698, 1835),
        ))
        assertThat(rects).containsExactly(
            ScreenRect(0, 95, 1080, 1159),
            ScreenRect(0, 1698, 1080, 2175),
        )
    }
}

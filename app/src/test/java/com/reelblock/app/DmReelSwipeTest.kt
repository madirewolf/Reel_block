package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DmReelSwipeTest {

    private val pager = "androidx.viewpager.widget.ViewPager"
    private fun page(i: Int, cls: String = pager) = DmReelSwipe.Page(i, cls)

    @Test
    fun `scrolling the comments of a DM reel is not a swipe`() {
        // Bug: the comments sheet scrolls with no page index; 3 s after the
        // reel opened that counted as a swipe and closed the friend's reel.
        val comments = DmReelSwipe.pageOf(-1, -1, "android.view.View")
        assertThat(comments).isNull()
        assertThat(DmReelSwipe.judge(baseline = null, scroll = comments).isSwipe).isFalse()
        assertThat(DmReelSwipe.judge(baseline = page(0), scroll = comments))
            .isEqualTo(DmReelSwipe.Verdict(false, page(0)))
    }

    @Test
    fun `the shared reel settling on page 0 is remembered, not blocked`() {
        assertThat(DmReelSwipe.judge(null, page(0))).isEqualTo(DmReelSwipe.Verdict(false, page(0)))
        assertThat(DmReelSwipe.judge(page(0), page(0))).isEqualTo(DmReelSwipe.Verdict(false, page(0)))
    }

    @Test
    fun `moving to another page is a swipe`() {
        assertThat(DmReelSwipe.judge(page(0), page(1))).isEqualTo(DmReelSwipe.Verdict(true, page(1)))
    }

    @Test
    fun `a swipe before the pager ever settled is still a swipe`() {
        // Seen on the phone: no settling scroll, then the swipe arrived as
        // page 1. It used to be taken as the baseline and let through.
        assertThat(DmReelSwipe.judge(null, page(1)).isSwipe).isTrue()
    }

    @Test
    fun `another scrolling view inside the reel is not paging`() {
        val list = page(4, cls = "androidx.recyclerview.widget.RecyclerView")
        assertThat(DmReelSwipe.judge(page(0), list)).isEqualTo(DmReelSwipe.Verdict(false, page(0)))
    }

    @Test
    fun `the page index comes from toIndex, then fromIndex`() {
        assertThat(DmReelSwipe.pageOf(0, 1, pager)).isEqualTo(page(1))
        assertThat(DmReelSwipe.pageOf(2, -1, pager)).isEqualTo(page(2))
    }
}

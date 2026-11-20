package com.reelblock.app

/**
 * Tells a swipe to the next reel apart from other scrolling while a reel
 * opened from a DM is playing.
 *
 * Instagram 457 opens a DM reel in a pager. Its scroll events carry the
 * page index: 0 for the shared reel, 1 and up for the reels Instagram
 * queues after it (seen on the phone: `0/0/5` while settling, `1/1/5`
 * after a swipe). Scrolling the comments sheet sends scroll events with
 * no index at all.
 *
 * The old rule treated any scroll without an index, 3 s after the reel
 * opened, as a swipe. Scrolling the comments of a friend's reel then threw
 * the user back to the chat.
 */
internal object DmReelSwipe {

    /** A page of a pager, as a scroll event reports it. */
    data class Page(val index: Int, val sourceClass: String?)

    /** Whether a scroll is a swipe, and the page to remember next. */
    data class Verdict(val isSwipe: Boolean, val baseline: Page?)

    /** The page a scroll event reports, or null if it carries no index. */
    fun pageOf(fromIndex: Int, toIndex: Int, sourceClass: CharSequence?): Page? {
        val index = when {
            toIndex >= 0 -> toIndex
            fromIndex >= 0 -> fromIndex
            else -> return null
        }
        return Page(index, sourceClass?.toString())
    }

    /**
     * [baseline] is the page the allowed reel sits on (null until a scroll
     * reports one). The first page seen is the shared reel settling, unless
     * it is already past page 0: that is a swipe made before the pager
     * ever reported settling. A scroll from another scrolling view (its
     * class differs) is not paging.
     */
    fun judge(baseline: Page?, scroll: Page?): Verdict = when {
        scroll == null -> Verdict(false, baseline)
        baseline == null -> Verdict(scroll.index > 0, scroll)
        scroll.sourceClass != baseline.sourceClass -> Verdict(false, baseline)
        scroll.index == baseline.index -> Verdict(false, baseline)
        else -> Verdict(true, scroll)
    }
}

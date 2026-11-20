package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bug: the block cover described in the README and onboarding was a
 * no-op, so the reel stayed visible while the service navigated away.
 * The cover is back; these tests pin its timing so it can never strand
 * Instagram behind an opaque window.
 */
class CoverTimingTest {

    @Test
    fun `cover always auto hides after the max`() {
        val t = CoverTiming(minVisibleMs = 250, maxVisibleMs = 1200)
        assertThat(t.onShow(now = 1_000)).isEqualTo(1200)
        assertThat(t.isShown).isTrue()
    }

    @Test
    fun `landing quickly still keeps the cover up for the minimum`() {
        val t = CoverTiming(minVisibleMs = 250, maxVisibleMs = 1200)
        t.onShow(now = 1_000)
        assertThat(t.delayBeforeHideOnLanding(now = 1_100)).isEqualTo(150)
    }

    @Test
    fun `landing after the minimum hides immediately`() {
        val t = CoverTiming(minVisibleMs = 250, maxVisibleMs = 1200)
        t.onShow(now = 1_000)
        assertThat(t.delayBeforeHideOnLanding(now = 1_600)).isEqualTo(0)
    }

    @Test
    fun `landing with no cover up does nothing`() {
        val t = CoverTiming()
        assertThat(t.delayBeforeHideOnLanding(now = 5_000)).isNull()
        t.onShow(now = 1_000)
        t.onHidden()
        assertThat(t.isShown).isFalse()
        assertThat(t.delayBeforeHideOnLanding(now = 5_000)).isNull()
    }

    @Test
    fun `a repeat block restarts the minimum from the latest show`() {
        val t = CoverTiming(minVisibleMs = 250, maxVisibleMs = 1200)
        t.onShow(now = 1_000)
        t.onShow(now = 2_000)
        assertThat(t.delayBeforeHideOnLanding(now = 2_050)).isEqualTo(200)
    }
}

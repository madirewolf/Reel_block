package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Bug: when Instagram ignored the service's own Home-tab click, the
 * click's accessibility echo was taken as the user leaving Reels. The
 * session dropped to IDLE, verification stopped retrying and the user
 * stayed on the Reels tab. Reproduced on an emulator with a test app
 * that swallows the first two tab switches.
 */
class SelfTabClickEchoTest {

    @Test
    fun `click echo of our own tab click is recognised once`() {
        val e = SelfTabClickEcho()
        e.noteSelfClick("home", now = 1_000)
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 1_150)).isTrue()
        // A second click on Home is the user's own tap.
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 1_300)).isFalse()
    }

    @Test
    fun `selection change is real evidence not an echo`() {
        val e = SelfTabClickEcho()
        e.noteSelfClick("home", now = 1_000)
        assertThat(e.consumeIfEcho(isClickEvent = false, eventTabLabel = "home", now = 1_100)).isFalse()
        // ... and the pending echo is still there for the click event.
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 1_120)).isTrue()
    }

    @Test
    fun `a different tab is the user's tap`() {
        val e = SelfTabClickEcho()
        e.noteSelfClick("home", now = 1_000)
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "profile", now = 1_100)).isFalse()
    }

    @Test
    fun `echo window expires`() {
        val e = SelfTabClickEcho(windowMs = 1_500)
        e.noteSelfClick("home", now = 1_000)
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 2_600)).isFalse()
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 2_700)).isFalse()
    }

    @Test
    fun `failed click leaves nothing to echo`() {
        val e = SelfTabClickEcho()
        e.noteSelfClick("home", now = 1_000)
        e.clear()
        assertThat(e.consumeIfEcho(isClickEvent = true, eventTabLabel = "home", now = 1_100)).isFalse()
    }
}

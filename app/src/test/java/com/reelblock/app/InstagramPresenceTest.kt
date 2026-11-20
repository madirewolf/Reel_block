package com.reelblock.app

import com.google.common.truth.Truth.assertThat
import com.reelblock.app.InstagramPresence.Decision
import com.reelblock.app.InstagramPresence.Verdict
import com.reelblock.app.InstagramPresence.WindowSummary
import org.junit.Test

/**
 * Bug: "leave Instagram and come back resets state" never happened. The
 * service is scoped to Instagram's packages, so Android never delivers a
 * launcher/other-app event and the reset branch in onAccessibilityEvent
 * was dead code. Departure is now detected by polling the window list;
 * these tests pin the decision rules.
 */
class InstagramPresenceTest {

    private val ig = "com.instagram.android"
    private fun presence() = InstagramPresence(setOf(ig, "com.instagram.lite"))

    private fun app(pkg: String?) = WindowSummary(isApplication = true, packageName = pkg)
    private fun system(pkg: String?) = WindowSummary(isApplication = false, packageName = pkg)

    @Test
    fun `instagram application window means instagram`() {
        val p = presence()
        assertThat(p.classify(listOf(system("com.android.systemui"), app(ig)))).isEqualTo(Verdict.INSTAGRAM)
    }

    @Test
    fun `split screen with another app still counts as instagram`() {
        val p = presence()
        assertThat(p.classify(listOf(app("com.whatsapp"), app(ig)))).isEqualTo(Verdict.INSTAGRAM)
    }

    @Test
    fun `launcher without instagram is another app`() {
        val p = presence()
        assertThat(p.classify(listOf(app("com.sec.android.app.launcher"), system("com.android.systemui"))))
            .isEqualTo(Verdict.OTHER_APP)
    }

    @Test
    fun `only system windows or unreadable roots is unknown`() {
        val p = presence()
        assertThat(p.classify(listOf(system("com.android.systemui")))).isEqualTo(Verdict.UNKNOWN)
        assertThat(p.classify(listOf(app(null)))).isEqualTo(Verdict.UNKNOWN)
        assertThat(p.classify(emptyList())).isEqualTo(Verdict.UNKNOWN)
    }

    @Test
    fun `two consecutive misses after seeing instagram means the user left`() {
        val p = presence()
        p.onWatchStart()
        assertThat(p.onCheck(Verdict.INSTAGRAM)).isEqualTo(Decision.KEEP_WATCHING)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.KEEP_WATCHING)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.LEFT)
    }

    @Test
    fun `a single miss followed by instagram is not a departure`() {
        val p = presence()
        p.onWatchStart()
        p.onCheck(Verdict.INSTAGRAM)
        p.onCheck(Verdict.OTHER_APP)
        assertThat(p.onCheck(Verdict.INSTAGRAM)).isEqualTo(Decision.KEEP_WATCHING)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.KEEP_WATCHING)
    }

    @Test
    fun `instagram tree evidence clears a pending miss`() {
        val p = presence()
        p.onWatchStart()
        p.onCheck(Verdict.INSTAGRAM)
        p.onCheck(Verdict.OTHER_APP)
        p.onInstagramEvidence()
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.KEEP_WATCHING)
    }

    @Test
    fun `notification shade or lock screen never counts as leaving`() {
        val p = presence()
        p.onWatchStart()
        p.onCheck(Verdict.INSTAGRAM)
        repeat(20) { assertThat(p.onCheck(Verdict.UNKNOWN)).isEqualTo(Decision.KEEP_WATCHING) }
        // ... and Instagram is still there afterwards: no reset.
        assertThat(p.onCheck(Verdict.INSTAGRAM)).isEqualTo(Decision.KEEP_WATCHING)
    }

    @Test
    fun `background event trickle never fires a departure without seeing instagram`() {
        // Instagram keeps sending a few events while it sits behind the
        // launcher. A watch started by those must stop quietly, not
        // log a second "left Instagram".
        val p = presence()
        p.onWatchStart()
        p.onCheck(Verdict.INSTAGRAM)
        p.onCheck(Verdict.OTHER_APP)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.LEFT)

        p.onWatchStart()
        val decisions = (1..5).map { p.onCheck(Verdict.OTHER_APP) }
        assertThat(decisions).doesNotContain(Decision.LEFT)
        assertThat(decisions.last()).isEqualTo(Decision.STOP)
    }

    @Test
    fun `returning to instagram re-arms departure detection`() {
        val p = presence()
        p.onWatchStart()
        p.onCheck(Verdict.INSTAGRAM)
        p.onCheck(Verdict.OTHER_APP)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.LEFT)

        p.onWatchStart()
        p.onInstagramEvidence()
        p.onCheck(Verdict.OTHER_APP)
        assertThat(p.onCheck(Verdict.OTHER_APP)).isEqualTo(Decision.LEFT)
    }
}

package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * The full-screen "Reeled in." cover drawn the instant a reel is
 * blocked, so the reel is never visible while the service navigates
 * away.
 *
 * Drawn as a TYPE_ACCESSIBILITY_OVERLAY window from the accessibility
 * service itself: it needs no "Display over other apps" permission,
 * sits above Instagram, and — being not focusable and not touchable —
 * never steals focus, swallows a BACK, or blocks a tap. Timing lives in
 * [CoverTiming]: up immediately, down as soon as the user has landed
 * off the reel, and never longer than ~1.2 s.
 *
 * (An earlier version showed a slide-in banner at the top of the
 * screen; it was removed because it appeared late and fought the
 * navigation. This cover appears synchronously with the block decision
 * and has no animation.)
 */
class BlockingOverlayController(private val service: AccessibilityService) {

    private val handler = Handler(Looper.getMainLooper())
    private val timing = CoverTiming()
    private var view: View? = null
    private val hideRunnable = Runnable { hideNow() }
    private var shownAt = 0L

    val isShowing: Boolean get() = view != null

    fun showBlocked() {
        val now = SystemClock.uptimeMillis()
        if (view == null) {
            val wm = service.getSystemService(WindowManager::class.java) ?: return
            val v = LayoutInflater.from(service).inflate(R.layout.blocking_overlay, null)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE,
            ).apply {
                title = "ReelItInCover"
            }
            try {
                wm.addView(v, params)
                view = v
                shownAt = now
                Log.d(TAG, "cover shown")
            } catch (t: Throwable) {
                Log.w(TAG, "cover addView failed", t)
                return
            }
        }
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, timing.onShow(now))
    }

    /** The user is off the reel — take the cover down once it has been
     *  up long enough not to flicker. */
    fun hideSoon() {
        val delay = timing.delayBeforeHideOnLanding(SystemClock.uptimeMillis()) ?: return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

    fun hide() = hideNow()

    private fun hideNow() {
        handler.removeCallbacks(hideRunnable)
        timing.onHidden()
        val v = view ?: return
        view = null
        Log.d(TAG, "cover hidden after ${SystemClock.uptimeMillis() - shownAt}ms")
        try {
            service.getSystemService(WindowManager::class.java)?.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "cover removeView failed", t)
        }
    }

    private companion object {
        const val TAG = "ReelBlock/Cover"
    }
}

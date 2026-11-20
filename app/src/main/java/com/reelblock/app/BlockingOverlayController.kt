package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
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
 * All window work runs on [OverlayThread], so the cover draws within a
 * frame even while the main thread is reading Instagram's tree. Calls
 * from the main thread are posted there in order.
 */
class BlockingOverlayController(private val service: AccessibilityService) {

    private val handler = OverlayThread.handler

    // Overlay thread only.
    private val timing = CoverTiming()
    private var view: View? = null
    private var inflated: View? = null
    private val hideRunnable = Runnable { hideNow() }
    private var shownAt = 0L

    /** Inflate the cover ahead of time. The first block after the service
     *  starts took ~120 ms to inflate it; a warm one takes ~10 ms. */
    fun warmUp() {
        handler.post { coverView() }
    }

    fun showBlocked() {
        val requestedAt = SystemClock.uptimeMillis()
        handler.post { showNow(requestedAt) }
    }

    /** The user is off the reel — take the cover down once it has been
     *  up long enough not to flicker. */
    fun hideSoon() {
        handler.post { hideSoonNow() }
    }

    fun hide() {
        handler.post { hideNow() }
    }

    private fun showNow(requestedAt: Long) {
        val now = SystemClock.uptimeMillis()
        if (view == null) {
            val wm = service.getSystemService(WindowManager::class.java) ?: return
            val v = coverView()
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
                Log.d(TAG, "cover shown ${SystemClock.uptimeMillis() - requestedAt}ms after request")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    v.viewTreeObserver.registerFrameCommitCallback {
                        Log.d(TAG, "cover on screen ${SystemClock.uptimeMillis() - requestedAt}ms after request")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "cover addView failed", t)
                return
            }
        }
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, timing.onShow(now))
    }

    /** One cover view, re-added on each block (removed views can be re-added). */
    private fun coverView(): View =
        inflated ?: LayoutInflater.from(service).inflate(R.layout.blocking_overlay, null).also { inflated = it }

    private fun hideSoonNow() {
        val delay = timing.delayBeforeHideOnLanding(SystemClock.uptimeMillis()) ?: return
        handler.removeCallbacks(hideRunnable)
        handler.postDelayed(hideRunnable, delay)
    }

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

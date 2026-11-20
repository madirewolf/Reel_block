package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager

/**
 * Opaque boxes drawn over reels that play inside the Home feed, for the
 * "Hide reels in Home feed" option. One box per reel on screen, moved as
 * the feed scrolls.
 *
 * Same window type as [BlockingOverlayController]: an accessibility
 * overlay that is not touchable, so the feed still scrolls under a box
 * and a tap on one goes through to the reel. Opening that reel is then
 * blocked like any other reel.
 */
class FeedReelCovers(private val service: AccessibilityService) {

    private val views = mutableListOf<View>()
    private var lastRects: List<ScreenRect> = emptyList()

    val isShowing: Boolean get() = views.isNotEmpty()

    fun show(rects: List<ScreenRect>) {
        if (rects == lastRects) return
        val wm = service.getSystemService(WindowManager::class.java) ?: return
        while (views.size > rects.size) {
            removeView(wm, views.removeAt(views.lastIndex))
        }
        rects.forEachIndexed { i, r ->
            val params = paramsFor(r)
            if (i < views.size) {
                try {
                    wm.updateViewLayout(views[i], params)
                } catch (t: Throwable) {
                    Log.w(TAG, "updateViewLayout failed", t)
                }
            } else {
                val v = LayoutInflater.from(service).inflate(R.layout.feed_reel_cover, null)
                try {
                    wm.addView(v, params)
                    views.add(v)
                } catch (t: Throwable) {
                    Log.w(TAG, "addView failed", t)
                }
            }
        }
        if (lastRects.isEmpty() && rects.isNotEmpty()) Log.d(TAG, "feed cover shown x${rects.size}")
        lastRects = rects
    }

    fun clear() {
        if (views.isEmpty()) return
        val wm = service.getSystemService(WindowManager::class.java)
        views.forEach { removeView(wm, it) }
        views.clear()
        lastRects = emptyList()
        Log.d(TAG, "feed covers cleared")
    }

    private fun removeView(wm: WindowManager?, v: View) {
        try {
            wm?.removeViewImmediate(v)
        } catch (t: Throwable) {
            Log.w(TAG, "removeView failed", t)
        }
    }

    private fun paramsFor(r: ScreenRect) = WindowManager.LayoutParams(
        r.width,
        r.height,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
        title = "ReelItInFeedCover"
    }

    private companion object {
        const val TAG = "ReelBlock/FeedCover"
    }
}

/** Plain screen rectangle, so the rect logic stays testable on the JVM. */
data class ScreenRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

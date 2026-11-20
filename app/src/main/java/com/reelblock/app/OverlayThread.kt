package com.reelblock.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * The thread that owns every cover window.
 *
 * The service's main thread reads Instagram's view tree, and one read can
 * take a few hundred ms. A window owned by the main thread cannot draw
 * until that read ends, so on the phone the block cover appeared after
 * the Reels tab had already bounced back to Home. A window added from
 * this thread draws on its own looper, within a frame of being asked.
 *
 * A window must be updated and removed on the thread that added it, so
 * [BlockingOverlayController] and [FeedReelCovers] do all of their
 * window work here.
 */
internal object OverlayThread {
    val handler: Handler by lazy {
        val thread = HandlerThread("ReelItInOverlay", Process.THREAD_PRIORITY_DISPLAY)
        thread.start()
        Handler(thread.looper)
    }
}

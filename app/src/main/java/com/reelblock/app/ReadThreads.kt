package com.reelblock.app

import android.os.Handler
import android.os.HandlerThread
import android.os.Process

/**
 * Threads that read Instagram's view tree, so the main thread never
 * waits on that IPC.
 *
 * One tree read is a few hundred ms while Reels plays, and a Home-feed
 * lookup 50-150 ms. On the main thread every event queued behind them:
 * a tap on the Reels tab reached the service ~450 ms late, after Reels
 * had drawn. Results go back to the main thread, which owns all session
 * state. Two threads, so feed covers keep up with scrolling while a
 * slow tree read is running.
 */
internal object ReadThreads {
    val tree: Handler by lazy { start("ReelItInTreeRead") }
    val feed: Handler by lazy { start("ReelItInFeedRead") }

    private fun start(name: String): Handler {
        val thread = HandlerThread(name, Process.THREAD_PRIORITY_FOREGROUND)
        thread.start()
        return Handler(thread.looper)
    }
}

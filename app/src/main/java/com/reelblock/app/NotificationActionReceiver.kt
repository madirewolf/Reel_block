package com.reelblock.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * The status notification's button: pause blocking for a minute, or
 * resume now. The service hears the change through [SettingsStore] and
 * turns itself back on when the minute is up.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val settings = SettingsStore(context)
        when (intent.action) {
            ACTION_PAUSE -> settings.pausedUntil = System.currentTimeMillis() + SettingsStore.PAUSE_MS
            ACTION_RESUME -> settings.pausedUntil = 0L
        }
    }

    companion object {
        const val ACTION_PAUSE = "com.reelblock.app.action.PAUSE"
        const val ACTION_RESUME = "com.reelblock.app.action.RESUME"
    }
}

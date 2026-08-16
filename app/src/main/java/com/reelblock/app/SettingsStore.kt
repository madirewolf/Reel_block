package com.reelblock.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper over SharedPreferences for the single persistent flag
 * Reel Block exposes: whether blocking is currently enabled. This is
 * separate from the Accessibility-service enabled state (which is owned
 * by the OS) — it lets the user pause blocking without having to flip
 * the system-level toggle.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var blockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, value).apply()

    /** When true, a reel opened from a DM thread is allowed to play —
     *  one reel only, swiping to the next is still blocked. When false,
     *  every reel is blocked, DM-shared or not. */
    var allowDmReels: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_DM_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_DM_REELS, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "reel_block_prefs"
        const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        const val KEY_ALLOW_DM_REELS = "allow_dm_reels"
    }
}

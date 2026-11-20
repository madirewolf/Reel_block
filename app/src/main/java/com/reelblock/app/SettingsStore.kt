package com.reelblock.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Thin wrapper over SharedPreferences for Reel It In's options. The
 * master switch is separate from the Accessibility-service enabled state
 * (which is owned by the OS) — it lets the user pause blocking without
 * having to flip the system-level toggle.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var blockingEnabled: Boolean
        get() = prefs.getBoolean(KEY_BLOCKING_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCKING_ENABLED, value).apply()

    /** Block the Reels tab. */
    var blockReelsTab: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_REELS_TAB, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_REELS_TAB, value).apply()

    /** Home feed: cover reels in the feed and block opening them. Kept
     *  under the old "hide feed reels" key so the choice carries over. */
    var blockHomeReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_HOME_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_HOME_REELS, value).apply()

    /** Block reels opened from Search & Explore, held tiles included. */
    var blockExploreReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_EXPLORE_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_EXPLORE_REELS, value).apply()

    /** Block the user's own reels, posts and reposts on their profile. */
    var blockOwnProfileReels: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_OWN_PROFILE, false)
        set(value) = prefs.edit().putBoolean(KEY_BLOCK_OWN_PROFILE, value).apply()

    /** When true, a reel opened from a DM thread is allowed to play —
     *  one reel only, swiping to the next is still blocked. When false,
     *  every reel opened from DMs is blocked. */
    var allowDmReels: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_DM_REELS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALLOW_DM_REELS, value).apply()

    /** Wall-clock ms until which blocking is paused from the notification; 0 = not paused. */
    var pausedUntil: Long
        get() = prefs.getLong(KEY_PAUSED_UNTIL, 0L)
        set(value) = prefs.edit().putLong(KEY_PAUSED_UNTIL, value).apply()

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = pausedUntil > now

    /** Blocking is on and not paused. */
    fun isActive(now: Long = System.currentTimeMillis()): Boolean = blockingEnabled && !isPaused(now)

    /** Show the ongoing "Reel It In is on" notification. */
    var showNotification: Boolean
        get() = prefs.getBoolean(KEY_SHOW_NOTIFICATION, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_NOTIFICATION, value).apply()

    /** How the block screen looks; see [CoverStyle]. */
    var coverStyle: String
        get() = prefs.getString(KEY_COVER_STYLE, CoverStyle.SOLID) ?: CoverStyle.SOLID
        set(value) = prefs.edit().putString(KEY_COVER_STYLE, value).apply()

    /** The block screen's color, as 0xAARRGGBB; 0 = the app's own background. */
    var coverColor: Int
        get() = prefs.getInt(KEY_COVER_COLOR, 0)
        set(value) = prefs.edit().putInt(KEY_COVER_COLOR, value).apply()

    /** Bumped each time a new photo is picked, so the cover reloads it. */
    var coverPhotoVersion: Long
        get() = prefs.getLong(KEY_COVER_PHOTO_VERSION, 0L)
        set(value) = prefs.edit().putLong(KEY_COVER_PHOTO_VERSION, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    companion object {
        const val PREFS_NAME = "reel_block_prefs"
        const val KEY_BLOCKING_ENABLED = "blocking_enabled"
        const val KEY_BLOCK_REELS_TAB = "block_reels_tab"
        const val KEY_BLOCK_HOME_REELS = "hide_feed_reels"
        const val KEY_BLOCK_EXPLORE_REELS = "block_explore_reels"
        const val KEY_BLOCK_OWN_PROFILE = "block_own_profile"
        const val KEY_ALLOW_DM_REELS = "allow_dm_reels"
        const val KEY_PAUSED_UNTIL = "paused_until"
        const val KEY_SHOW_NOTIFICATION = "show_notification"
        const val KEY_COVER_STYLE = "cover_style"
        const val KEY_COVER_COLOR = "cover_color"
        const val KEY_COVER_PHOTO_VERSION = "cover_photo_version"

        /** Keys whose change alters what gets blocked. */
        val POLICY_KEYS = setOf(
            KEY_BLOCK_REELS_TAB, KEY_BLOCK_HOME_REELS, KEY_BLOCK_EXPLORE_REELS,
            KEY_BLOCK_OWN_PROFILE, KEY_ALLOW_DM_REELS,
        )

        /** Keys that change how the block screen looks. */
        val COVER_KEYS = setOf(KEY_COVER_STYLE, KEY_COVER_COLOR, KEY_COVER_PHOTO_VERSION)

        /** One pause from the notification. */
        const val PAUSE_MS = 60_000L
    }
}

/** Block screen styles. */
object CoverStyle {
    const val SOLID = "solid"
    const val DOTS = "dots"
    const val STRIPES = "stripes"
    const val GRID = "grid"
    const val PHOTO = "photo"

    val PATTERNS = listOf(DOTS, STRIPES, GRID)

    /** Picked photos are copied here, inside the app's own storage. */
    const val PHOTO_FILE = "cover_photo.jpg"
}

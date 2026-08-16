package com.reelblock.app

import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager

/**
 * Helpers for inspecting runtime permissions relevant to Reel Block —
 * pulled into one place because three different Activities care about
 * them.
 */
object ServiceStatus {

    /**
     * Is the Accessibility Service we declared currently enabled by the
     * user? AccessibilityManager is the source of truth for "bound and
     * running"; SECURE_ENABLED_ACCESSIBILITY_SERVICES is a decent
     * fallback for pre-install-time checks on edge cases.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val manager = context.getSystemService(AccessibilityManager::class.java) ?: return false
        val enabled = manager.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        val expected = context.packageName + "/" + ReelBlockerService::class.java.name
        if (enabled.any { it.id == expected }) return true

        // Some OEMs don't update the Manager result until after a short
        // delay; fall back to the secure setting as a tiebreaker.
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(raw) }
        for (entry in splitter) {
            if (entry.equals(expected, ignoreCase = true)) return true
        }
        return false
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)
}

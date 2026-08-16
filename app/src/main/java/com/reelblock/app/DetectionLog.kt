package com.reelblock.app

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * An in-memory ring buffer of the last N detections made by the
 * Accessibility Service. Exposed to the UI via the Log screen so the
 * user can see what Reel Block is doing in real time — valuable both
 * as reassurance and for tuning detection heuristics on new Instagram
 * releases.
 */
object DetectionLog {

    private const val MAX_ENTRIES = 400

    data class Entry(
        val timestamp: Long,
        val screen: ScreenType,
        val action: SessionStateMachine.Action,
        val mode: SessionStateMachine.Mode,
        val modeBefore: SessionStateMachine.Mode? = null,
        val previousScreenBefore: ScreenType? = null,
        val previousScreenAfter: ScreenType? = null,
        val signature: String?,
        val note: String?,
        /** Compact description of interesting IDs/contentDescriptions
         *  the detector saw — for diagnosing "why wasn't this blocked?" */
        val diagnostics: String?,
        /** Raw fields captured directly off the AccessibilityEvent —
         *  present only when the entry was produced in response to one.
         *  These are the fields that survive every Instagram release and
         *  that we use for the instant class-name fast path. */
        val eventType: String? = null,
        val eventClassName: String? = null,
        val packageName: String? = null,
        val treeSource: String? = null,
        val selectedBottomTab: String? = null,
        val lastNonReelsBottomTab: String? = null,
        val blockRoute: String? = null,
        val blockSucceeded: Boolean? = null,
        val rootPackageName: String? = null,
        val sourcePackageName: String? = null,
        val nodeCount: Int? = null,
        val treeDepth: Int? = null,
        val interestingIds: List<String> = emptyList(),
        val sampledDescriptions: List<String> = emptyList(),
        /** Latency from onAccessibilityEvent entry to classification in ms. */
        val handleLatencyMs: Long? = null,
        /** True when the classification came from the class-name fast path
         *  rather than from a node-tree scan. */
        val fastPath: Boolean = false,
    )

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<() -> Unit>()
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun record(entry: Entry) {
        entries.addFirst(entry)
        while (entries.size > MAX_ENTRIES) entries.removeLast()
        listeners.toList().forEach { it.invoke() }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    /** Timestamp of the most recent entry, or null if none. Used to
     *  surface "service is alive" signals without leaking full entries. */
    @Synchronized
    fun lastEntryAt(): Long? = entries.firstOrNull()?.timestamp

    @Synchronized
    fun clear() {
        entries.clear()
        listeners.toList().forEach { it.invoke() }
    }

    @Synchronized
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    @Synchronized
    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    fun formatTime(epochMs: Long): String = formatter.format(Date(epochMs))
}

package com.reelblock.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat

/**
 * The heart of Reel Block.
 *
 * This AccessibilityService subscribes to window/content/scroll/select
 * events emitted by Instagram. For each event it:
 *
 *  1. Snapshots the relevant subtree — **preferring `event.source`** over
 *     `rootInActiveWindow`, because the latter is frequently null during
 *     window transitions (Google issuetracker #223809542) and we were
 *     losing signals because of it.
 *  2. Asks [ReelDetector] to classify the screen + extract a reel sig.
 *  3. Feeds the result into [SessionStateMachine], which returns an
 *     Action (ALLOW/BLOCK/NONE).
 *  4. On BLOCK, puts up the full-screen cover and routes away from Reels.
 *
 * The service is registered for Instagram's packages only, so it never
 * hears about the launcher or other apps. Leaving Instagram is detected
 * by polling the window list ([InstagramPresence]) while a session is
 * live, which is what resets state between visits.
 *
 * Every handled event is recorded to [DetectionLog] along with a
 * diagnostic summary. A plain ongoing notification says whether
 * blocking is on and offers a one-minute pause.
 */
class ReelBlockerService : AccessibilityService() {

    private val stateMachine = SessionStateMachine()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var overlay: BlockingOverlayController
    private lateinit var settings: SettingsStore
    private lateinit var notifMgr: NotificationManager

    /** Throttle repeat actions against the exact same blocked screen. */
    private var lastBlockAt: Long = 0L
    private var lastBlockScreen: ScreenType? = null

    /** Most recent non-Reels bottom-nav tab seen inside Instagram. */
    private var lastNonReelsBottomTab: String? = null

    /** Most recent bottom tab seen selected, the Reels tab included:
     *  where a reel on screen was opened from. */
    private var lastSelectedTab: String? = null

    /** Whose profile the user is on; see [ProfileContextTracker]. */
    private val profileTracker = ProfileContextTracker()

    /** Timestamp when a DM-launched reel became allowed. */
    private var dmAllowedSinceMs: Long = 0L

    /** Pager page of the allowed DM reel; see [DmReelSwipe]. */
    private var dmReelBaseline: DmReelSwipe.Page? = null

    /** When a cover went up on a scroll that looked like a DM-reel swipe,
     *  before the tree confirmed it (uptime ms; 0 = none). */
    private var earlyDmSwipeCoverAt = 0L

    /** Dedupe state for "Instagram event but no queryable tree" bursts —
     *  fired en masse while another window covers Instagram. */
    private var lastNullTreeRecordAt: Long = 0L
    private var suppressedNullTreeEvents: Int = 0

    /** Dedupe for repeated identical log entries — a reel playing on a
     *  blocked screen fires ~50 identical events/sec, which previously
     *  filled the whole ring buffer inside 10 seconds. */
    private var lastRecordKey: String? = null
    private var lastRecordKeyAt: Long = 0L
    private var suppressedRepeats: Int = 0

    /** Timestamp of the last event whose tree classified as a blockable
     *  screen (REELS_TAB / REEL_VIEWER). This — NOT `rootInActiveWindow`
     *  — is the truth signal for "the user is still on Instagram looking
     *  at reels": Samsung reports a null or launcher root while
     *  Instagram is demonstrably foreground and streaming events, and
     *  a covered/backgrounded Instagram cannot produce these
     *  classifications because its tree is unreachable. */
    private var lastBlockedScreenEventAt: Long = 0L

    /**
     * Tree walks are coalesced: an event that needs the tree only parks a
     * copy here; one read on [ReadThreads.tree] then covers every event
     * that arrived while the previous read was running.
     *
     * Reading Instagram's tree costs ~100-150 ms of IPC (Instagram's UI
     * thread answers every node request between video frames), while it
     * fires a content change every ~80 ms. Walking once per event built
     * an ever-growing queue on the main thread: the service acted on
     * screens seconds old, blocked the reel after the one on screen, and
     * the cover and BACK presses waited behind the backlog (a phone log
     * showed the cover stuck up for 3.4 s).
     */
    private var pendingWalkEvent: AccessibilityEvent? = null

    /** Boxes over reels inside the Home feed ("Hide reels in Home feed"). */
    private lateinit var feedCovers: FeedReelCovers

    /** True while the last classified screen was the Home tab. */
    private var onHomeFeed = false
    private var feedRefreshPosted = false
    private var feedReadInFlight = false
    private var feedRefreshAgain = false

    /** Only touched on [ReadThreads.feed]. */
    private var feedCoverCountLogged = -1
    private val feedHold = FeedCoverHold()
    private val feedHoldEnd = Runnable {
        feedHold.release()
        if (onHomeFeed) scheduleFeedRefresh() else feedCovers.clear()
    }
    private val feedRefreshRunnable = Runnable { refreshFeedReelCovers() }
    /** A tree read is running on [ReadThreads.tree]. Main thread only. */
    private var treeReadInFlight = false

    /** Uptime of our last block action (cover + navigation). */
    private var lastBlockActionAt = 0L
    private var destroyed = false

    /** Our own tab clicks echo back as click events; see [SelfTabClickEcho]. */
    private val selfTabClickEcho = SelfTabClickEcho()

    /** Leave-Instagram detection: polls the window list while a session
     *  is live, because no event arrives when the user goes elsewhere. */
    private val presence = InstagramPresence(INSTAGRAM_PACKAGES)
    private var presenceWatchActive = false
    private val presenceCheck = Runnable { checkInstagramPresence() }

    /**
     * Instagram is not in front (Home, recents, another app, the shade,
     * the lock screen). Every cover is down and stays down, and events
     * are not acted on, until Instagram has focus again.
     */
    private var igAway = false
    private var screenOff = false
    /** Instagram's window was still on screen at the last check (under the shade, say). */
    private var igVisibleBehind = false
    /** The last Instagram event ignored while away, looked at again on return. */
    private var awayDroppedEvent: AccessibilityEvent? = null
    /** Windows Instagram's events came from, newest last. Main thread only. */
    private val igWindowIds = LinkedHashSet<Int>()
    /** The last non-Instagram app window seen in front, so polls skip re-reading it. */
    private var lastAwayWindowId = -1
    private var guardTickPosted = false
    private var frontCheckInFlight = false
    private var lastFrontCheckAt = 0L
    private val guardTick = Runnable {
        guardTickPosted = false
        requestFrontCheck()
    }
    private var screenReceiver: BroadcastReceiver? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    /** Turns blocking back on when a pause from the notification ends. */
    private val pauseEnd = Runnable { onPauseEnded() }

    /** Debug builds only — see [DebugTreeDump]. */
    private var debugDumpReceiver: BroadcastReceiver? = null

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val changed = key ?: return@OnSharedPreferenceChangeListener
        when {
            changed == SettingsStore.KEY_BLOCKING_ENABLED ||
                changed == SettingsStore.KEY_PAUSED_UNTIL -> applyActiveState()
            changed in SettingsStore.POLICY_KEYS -> {
                syncPolicy()
                if (changed == SettingsStore.KEY_BLOCK_HOME_REELS) {
                    if (settings.blockHomeReels) {
                        scheduleFeedRefresh()
                    } else {
                        endFeedHold()
                        feedCovers.clear()
                    }
                }
            }
            changed in SettingsStore.COVER_KEYS -> overlay.reloadStyle()
            changed == SettingsStore.KEY_SHOW_NOTIFICATION -> updateStatusNotification()
        }
    }

    private data class NodeSelection(
        val node: AccessibilityNodeInfo?,
        val sourceLabel: String,
        val rootPackageName: String?,
        val sourcePackageName: String?,
    )

    private data class BlockAttempt(
        val route: String,
        val succeeded: Boolean,
        val skipReason: String? = null,
    )

    /** Outcome of evaluating whether a block should fire for an event. */
    private data class BlockDecision(
        val fire: Boolean,
        /** Human-readable explanation logged on every event — the primary
         *  tool for diagnosing "why did we / didn't we block". */
        val reason: String,
    )

    /** Tracks consecutive physical block actions without an intervening
     *  screen transition. If we exceed the ceiling we stop firing BACK
     *  entirely until the screen changes — the historic bug was that
     *  stale REELS_TAB events on Samsung triggered repeat BACKs that
     *  ejected the user from Instagram. */
    private var consecutiveBlockActions: Int = 0
    private var lastFiredBlockScreen: ScreenType? = null

    override fun onCreate() {
        super.onCreate()
        overlay = BlockingOverlayController(this).also { it.warmUp() }
        feedCovers = FeedReelCovers(this)
        settings = SettingsStore(this)
        syncPolicy()
        settings.registerListener(settingsListener)
        notifMgr = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        schedulePauseEnd()
    }

    /** Copies the per-place switches into the state machine. */
    private fun syncPolicy() {
        stateMachine.blockReelsTab = settings.blockReelsTab
        stateMachine.blockHomeReels = settings.blockHomeReels
        stateMachine.blockExploreReels = settings.blockExploreReels
        stateMachine.blockOwnProfileReels = settings.blockOwnProfileReels
        stateMachine.allowDmReels = settings.allowDmReels
    }

    /** Blocking was turned on or off, paused or resumed. */
    private fun applyActiveState() {
        if (settings.isActive()) {
            scheduleFeedRefresh()
        } else {
            stateMachine.reset()
            clearBlockingCooldown()
            overlay.hide()
            endFeedHold()
            feedCovers.clear()
        }
        schedulePauseEnd()
        updateStatusNotification()
    }

    private fun schedulePauseEnd() {
        handler.removeCallbacks(pauseEnd)
        val left = settings.pausedUntil - System.currentTimeMillis()
        if (left > 0) handler.postDelayed(pauseEnd, left + 50)
    }

    private fun onPauseEnded() {
        // Clearing the pause reaches [applyActiveState] through the listener.
        if (settings.pausedUntil != 0L) settings.pausedUntil = 0L else applyActiveState()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        configureRuntimeServiceInfo()
        val configDiagnostics = describeCurrentServiceInfo()
        Log.i(TAG, "Accessibility service connected: $configDiagnostics")
        stateMachine.reset()
        clearBlockingCooldown()
        lastNonReelsBottomTab = null
        lastSelectedTab = null
        profileTracker.reset()
        registerDebugDumpReceiver()
        registerScreenReceiver()
        DetectionLog.record(
            DetectionLog.Entry(
                timestamp = System.currentTimeMillis(),
                screen = ScreenType.UNKNOWN,
                action = SessionStateMachine.Action.NONE,
                mode = stateMachine.currentMode(),
                previousScreenAfter = stateMachine.currentPrevScreen(),
                signature = null,
                note = "serviceConnected",
                diagnostics = configDiagnostics,
            )
        )
        // An ongoing notification: not a true FGS (AccessibilityService
        // already has a privileged binding), but the ongoing flag gives
        // OEM task-killers reason to leave us alone.
        updateStatusNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val handleStart = System.currentTimeMillis()
        val pkg = event.packageName?.toString() ?: return
        val eventClassName = event.className?.toString()

        if (!isInstagramPackage(pkg)) {
            // Defensive only: the package filter means Android does not
            // deliver other apps' events, so leaving Instagram is caught
            // by the window-list poll in [checkInstagramPresence].
            resetSessionState()
            return
        }

        startPresenceWatch()
        noteInstagramWindow(event.windowId)
        val isTap = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
        if (isTap || event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Only the foreground app receives taps and scrolls.
            presence.onInstagramEvidence()
        }
        if (igAway) {
            // A tap only reaches the app in front; anything else needs a look.
            if (isTap && !screenOff) {
                applyFrontVerdict(FrontWindow.Verdict.INSTAGRAM, event.windowId)
            } else {
                requestFrontCheck(throttled = true)
            }
            if (igAway) {
                @Suppress("DEPRECATION")
                awayDroppedEvent = AccessibilityEvent.obtain(event)
                return
            }
        }

        if (onHomeFeed) scheduleFeedRefresh()

        // ---- FAST PATH A: classify directly from the event itself ----
        // Tab taps fire TYPE_VIEW_CLICKED / TYPE_VIEW_SELECTED whose
        // contentDescription is the tab label ("Reels", "Home", …).
        // This is the *most reliable* signal for bottom-nav interactions
        // because it names the tab the user actually pressed, rather
        // than leaving us to disambiguate two concurrently-"selected"
        // tabs in the stale accessibility tree. Prioritise it above
        // the class-name fast path and the tree walk.
        // The source id tells a bottom-nav tab apart from look-alike
        // controls (another user's profile has a "Reels" grid tab).
        val isTapEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED
        val tapSourceId: String? = if (isTapEvent) {
            try {
                event.source?.viewIdResourceName
            } catch (_: Throwable) {
                null
            }
        } else null
        val navEventTab = if (isTapEvent) {
            ReelDetector.bottomNavTabForEvent(
                event.contentDescription,
                tapSourceId,
                isClick = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
            )
        } else null
        val rawNavEventScreen = ReelDetector.classifyByBottomNavEvent(
            event.eventType,
            event.contentDescription,
            tapSourceId,
        )
        // The echo of our own tab click says nothing about whether
        // Instagram switched tabs; let the tree walk decide instead.
        val navEventScreen = if (
            (rawNavEventScreen == ScreenType.OTHER || rawNavEventScreen == ScreenType.DM_INBOX) &&
            selfTabClickEcho.consumeIfEcho(
                isClickEvent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED,
                eventTabLabel = navEventTab,
                now = handleStart,
            )
        ) null else rawNavEventScreen

        // ---- FAST PATH B: classify from event.className alone ----
        // Instagram's activity/fragment class names (ClipsViewerActivity,
        // DirectThreadFragment, …) are available synchronously on
        // TYPE_WINDOW_STATE_CHANGED *before* the node tree is queryable,
        // and they survive every release we've seen. A hit here blocks in
        // a few ms instead of waiting for the view tree to be walked.
        val fastScreen: ScreenType? = navEventScreen ?: ReelDetector.classifyByClassName(eventClassName)

        if (navEventScreen != null && navEventTab != null) {
            // A real tap on a bottom tab: the user is on that tab now.
            profileTracker.onTabTap(navEventTab)
            lastSelectedTab = navEventTab
        }

        if (!settings.isActive()) {
            // Still emit a log entry so the user can confirm events are
            // arriving even while paused; without this a "paused" app
            // looks identical to a "dead" app in the UI.
            recordEvent(
                event = event,
                screen = fastScreen ?: ScreenType.UNKNOWN,
                action = SessionStateMachine.Action.NONE,
                signature = null,
                diagnostics = "paused cls=$eventClassName",
                treeSource = "none",
                selectedBottomTab = null,
                handleLatencyMs = System.currentTimeMillis() - handleStart,
                fastPath = fastScreen != null,
            )
            return
        }

        // A real tap on the Reels tab: cover now, before Instagram draws
        // the tab, and block without waiting for a tree that still shows
        // the old tab selected.
        val freshReelsTap = settings.blockReelsTab && CoverPolicy.isFreshReelsTabTap(
            isTapEvent = isTapEvent,
            sourceViewId = tapSourceId,
            eventAgeMs = SystemClock.uptimeMillis() - event.eventTime,
        )
        if (freshReelsTap) showBlockCover()
        // A swipe off an allowed DM reel: cover now. The tree read that
        // confirms it can take half a second while Instagram animates,
        // and the next reel plays meanwhile. Dropped if the read says the
        // user is still on the allowed reel.
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            stateMachine.currentMode() == SessionStateMachine.Mode.DM_ALLOWED_REEL &&
            DmReelSwipe.judge(dmReelBaseline, scrollPageOf(event)).isSwipe
        ) {
            showBlockCover()
            earlyDmSwipeCoverAt = SystemClock.uptimeMillis()
            Log.d(TAG, "early cover: DM reel swipe idx=${event.toIndex} base=${dmReelBaseline?.index} age=${SystemClock.uptimeMillis() - event.eventTime}ms")
        }
        if (navEventTab != null) {
            Log.d(TAG, "tap tab=$navEventTab type=${event.eventType} age=${SystemClock.uptimeMillis() - event.eventTime}ms fresh=$freshReelsTap src=$tapSourceId cls=$eventClassName")
        }

        // If the fast path already says "reel viewer" or "reels tab", we
        // can act immediately — but we still want the tree for signature
        // tracking in DM-allowed mode (so swipes between reels are caught).
        if (fastScreen == ScreenType.REEL_VIEWER || fastScreen == ScreenType.REELS_TAB) {
            handleClassifiedScreen(
                event = event,
                eventClassName = eventClassName,
                screen = fastScreen,
                handleStart = handleStart,
                trustTap = freshReelsTap,
            )
            return
        }
        if (fastScreen == ScreenType.DM_THREAD || fastScreen == ScreenType.DM_INBOX) {
            // DM / inbox screens don't need a tree — latch the screen so
            // the next reel viewer is allowed.
            if (navEventScreen == ScreenType.DM_INBOX) {
                lastNonReelsBottomTab = ReelDetector.TAB_DIRECT
            }
            handleClassifiedScreen(
                event = event,
                eventClassName = eventClassName,
                screen = fastScreen,
                handleStart = handleStart,
            )
            return
        }
        if (navEventScreen == ScreenType.OTHER) {
            // User explicitly tapped a non-Reels bottom-nav tab
            // (Home / Search / Profile / Notifications). Short-circuit
            // tree detection entirely — the tree may still show
            // Reels as selected due to Samsung's stale-selection
            // window, which would otherwise trigger a bogus
            // home-tab-click. The event is authoritative.
            //
            // Stash the tab label so the next Reels-block has a valid
            // navigation target even before the slow-path tree walk
            // has a chance to repopulate it.
            navEventTab?.let { tab -> lastNonReelsBottomTab = tab }
            setOnHomeFeed(navEventTab == ReelDetector.TAB_HOME, "nav=$navEventTab")
            // Explicit tab tap = definitive "user left DM context".
            // Wipe any stale DM-allowed-reel mode + lastKnownScreen
            // that could have otherwise let the next reel through as
            // "coming from a DM thread we saw a while ago".
            // This resets the mode before handleClassifiedScreen runs,
            // so its BLOCKING->IDLE check can't see the block end: take
            // the cover down here.
            if (stateMachine.noteStableNonDmSurface()) {
                overlay.hideSoon()
            }
            handleClassifiedScreen(
                event = event,
                eventClassName = eventClassName,
                screen = ScreenType.OTHER,
                handleStart = handleStart,
            )
            return
        }

        // ---- SLOW PATH: walk the tree (coalesced) ----
        enqueueTreeWalk(event)
    }

    private fun enqueueTreeWalk(event: AccessibilityEvent) {
        val previous = pendingWalkEvent
        // A scroll carries the pager page index the DM-reel swipe check
        // needs; a later content change must not overwrite it.
        val keepPrevious = previous != null &&
            previous.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED
        if (!keepPrevious) {
            @Suppress("DEPRECATION")
            pendingWalkEvent = AccessibilityEvent.obtain(event)
        }
        if (!treeReadInFlight) startTreeRead()
    }

    /**
     * Reads the tree for the parked event on [ReadThreads.tree] and hands
     * the result back to [finishTreeWalk] on the main thread.
     */
    private fun startTreeRead() {
        val event = pendingWalkEvent ?: return
        pendingWalkEvent = null
        if (!settings.isActive() || igAway) return
        treeReadInFlight = true
        val handleStart = System.currentTimeMillis()
        val readStartedAt = SystemClock.uptimeMillis()
        ReadThreads.tree.post {
            var selection = NodeSelection(null, "none", null, null)
            var snapshot: NodeSnapshot? = null
            try {
                val sourceNode: AccessibilityNodeInfo? = try {
                    event.source
                } catch (_: Throwable) {
                    null
                }
                val rootNode: AccessibilityNodeInfo? = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        getRootInActiveWindow(WALK_PREFETCH)
                    } else {
                        rootInActiveWindow
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "rootInActiveWindow threw", t)
                    null
                }
                val windowNode = if (isInstagramPackage(rootNode?.packageName?.toString().orEmpty())) {
                    null
                } else {
                    focusedInstagramRoot()
                }
                selection = selectInstagramNode(rootNode, sourceNode, windowNode)
                snapshot = selection.node?.toSnapshot()
                // A long-press preview's menu has focus: the preview
                // itself is behind it, in Instagram's main window.
                if (ReelDetector.isPeekMenu(snapshot)) {
                    peekBehindMenu()?.let { (peekNode, peekSnapshot) ->
                        selection = selection.copy(node = peekNode, sourceLabel = "peek")
                        snapshot = peekSnapshot
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "tree read failed", t)
            }
            val readSelection = selection
            val readSnapshot = snapshot
            handler.post {
                treeReadInFlight = false
                if (destroyed) return@post
                if (CoverPolicy.isTreeReadStale(readStartedAt, lastBlockActionAt)) {
                    // Read again so the screen we moved the user to
                    // still gets classified.
                    if (pendingWalkEvent == null) pendingWalkEvent = event
                } else {
                    finishTreeWalk(event, handleStart, readSelection, readSnapshot)
                    if (CoverPolicy.dropEarlyCover(
                            coverShownAt = earlyDmSwipeCoverAt,
                            readStartedAt = readStartedAt,
                            blocking = stateMachine.currentMode() == SessionStateMachine.Mode.BLOCKING,
                        )
                    ) {
                        earlyDmSwipeCoverAt = 0L
                        overlay.hideSoon()
                        Log.d(TAG, "early cover dropped: still on the allowed reel")
                    }
                }
                if (pendingWalkEvent != null) startTreeRead()
            }
        }
    }

    private fun finishTreeWalk(
        event: AccessibilityEvent,
        handleStart: Long,
        selection: NodeSelection,
        snapshot: NodeSnapshot?,
    ) {
        if (!settings.isActive() || igAway) return
        val eventClassName = event.className?.toString()

        if (snapshot == null) {
            // Do NOT reset the state machine here. Samsung emits
            // null-tree events as transient hiccups DURING active
            // Instagram use — a field log showed one such hiccup wiping
            // DM_ALLOWED_REEL mid-playback (the very next reel-viewer
            // event then blocked a legitimately allowed reel) and
            // killing BLOCKING mode mid-navigation (queued BACKs and
            // the watchdog check mode and silently no-oped). Genuine
            // departures are handled fine without a reset: the next
            // real screen classification (OTHER / DM / reels) performs
            // its own transition.
            //
            // Instagram also keeps firing these while another window is
            // on top (notification shade, launcher) — 190 in 300 ms in
            // one log, wiping the ring buffer. Record at most one
            // null-tree entry per second; count the rest.
            val now = System.currentTimeMillis()
            if (now - lastNullTreeRecordAt < NULL_TREE_LOG_INTERVAL_MS) {
                suppressedNullTreeEvents += 1
                return
            }
            val suppressedNote = if (suppressedNullTreeEvents > 0) {
                " suppressed=$suppressedNullTreeEvents"
            } else ""
            lastNullTreeRecordAt = now
            suppressedNullTreeEvents = 0
            recordEvent(
                event = event,
                screen = ScreenType.UNKNOWN,
                action = SessionStateMachine.Action.NONE,
                signature = null,
                diagnostics = "tree=${selection.sourceLabel} cls=$eventClassName rootPkg=${selection.rootPackageName ?: "null"} sourcePkg=${selection.sourcePackageName ?: "null"}$suppressedNote",
                treeSource = selection.sourceLabel,
                selectedBottomTab = null,
                lastNonReelsBottomTab = lastNonReelsBottomTab,
                rootPackageName = selection.rootPackageName,
                sourcePackageName = selection.sourcePackageName,
                handleLatencyMs = System.currentTimeMillis() - handleStart,
                fastPath = false,
            )
            return
        }
        lastNullTreeRecordAt = 0L
        suppressedNullTreeEvents = 0
        // A readable Instagram tree means Instagram is on screen.
        presence.onInstagramEvidence()

        val treeDebug = ReelDetector.inspectTree(snapshot)
        val wholeWindow = selection.sourceLabel == "root" || selection.sourceLabel == "window"
        var screen = ReelDetector.detectScreen(snapshot)
        // A read of one scrolled list has no tab bar around it, and a Home
        // feed list could pass for a reel player: BACK then sent the feed
        // to the top. From such a read only the player's own signs count.
        if (selection.sourceLabel == "source" && screen == ScreenType.REEL_VIEWER &&
            !ReelDetector.hasStrongReelMarkers(snapshot)
        ) {
            screen = ScreenType.UNKNOWN
        }
        val selectedBottomTab = ReelDetector.extractSelectedBottomTab(snapshot)
        if (!selectedBottomTab.isNullOrBlank()) lastSelectedTab = selectedBottomTab
        if (wholeWindow && screen != ScreenType.REEL_VIEWER && screen != ScreenType.REELS_TAB &&
            screen != ScreenType.UNKNOWN
        ) {
            profileTracker.onRead(ReelDetector.profilePageKind(snapshot), selectedBottomTab)
        }
        // "Stable non-DM surface" = user is demonstrably outside of a DM
        // thread (any main-app tab is visible). Previously required a
        // *selectedBottomTab* read, which Samsung sometimes fails to
        // populate during the first 300-700 ms after a screen change
        // even though the bottom nav is visibly drawn — the net effect
        // was DM context lingering stuck on `lastKnownScreen=DM_THREAD`
        // past the user's exit from DMs, and the next reel would slip
        // through as "came from DM, allow". Fall back to "the tab bar
        // is on screen" which is a strict superset: the
        // presence of the bottom nav proves we are not in a DM overlay
        // or fullscreen reel viewer.
        val hasMainTabBar = ReelDetector.treeHasMainTabBar(snapshot)
        val stableNonDmSurface = screen == ScreenType.OTHER &&
            (((!selectedBottomTab.isNullOrBlank()) && selectedBottomTab != "reels") ||
                hasMainTabBar)
        if (!selectedBottomTab.isNullOrBlank() && selectedBottomTab != "reels") {
            lastNonReelsBottomTab = selectedBottomTab
        }
        val signature = if (screen == ScreenType.REEL_VIEWER || screen == ScreenType.REELS_TAB) {
            ReelDetector.extractReelSignature(snapshot)
        } else null
        val diagnostics = buildString {
            append("tree=")
            append(selection.sourceLabel)
            append(" cls=")
            append(eventClassName ?: "null")
            append(" rootPkg=")
            append(selection.rootPackageName ?: "null")
            append(" sourcePkg=")
            append(selection.sourcePackageName ?: "null")
            append(" ")
            append(ReelDetector.diagnosticSummary(snapshot))
        }

        val priorMode = stateMachine.currentMode()
        val previousScreenBefore = stateMachine.currentPrevScreen()
        if (screen == ScreenType.REELS_TAB || screen == ScreenType.REEL_VIEWER) {
            lastBlockedScreenEventAt = System.currentTimeMillis()
        }
        val dmReelSwipe = isDmReelSwipeScroll(event, screen, priorMode)
        val openedFromDm = screen == ScreenType.REEL_VIEWER && openedFromDm(snapshot)

        val action = if (dmReelSwipe) {
            stateMachine.onReelScroll()
        } else {
            stateMachine.handleScreen(
                screen,
                signatureForStateMachine(screen, signature, priorMode, openedFromDm),
                reelOrigin(),
                openedFromDm,
            )
        }
        if (stableNonDmSurface) {
            stateMachine.noteStableNonDmSurface()
        }
        if (priorMode == SessionStateMachine.Mode.BLOCKING &&
            stateMachine.currentMode() == SessionStateMachine.Mode.IDLE
        ) {
            clearBlockingCooldown()
            overlay.hideSoon()
        }
        updateDmAllowedWindow(priorMode, action)
        // Intentionally NOT calling clearBlockingCooldown() here on
        // stableNonDmSurface. It used to fire whenever home's tree
        // settled with the bottom nav visible, which is 100–300 ms
        // after a REELS_TAB block — so the cooldown that was supposed
        // to protect us from stale REELS_TAB events got wiped early,
        // and a stale event that arrived ~400 ms after the block
        // (Samsung's acessibility lag) would re-fire and mis-route
        // the next unrelated tap (DM icon, Explore, etc). Let the
        // 1500 ms cooldown expire naturally instead.
        val decision = decideBlock(
            action = action,
            screen = screen,
            priorMode = priorMode,
            previousScreenBefore = previousScreenBefore,
        )
        val blockAttempt = if (decision.fire) {
            triggerBlock(screen = screen, activeNode = selection.node)
        } else {
            null
        }
        // After the block, so a block's feed-cover hold is already on.
        setOnHomeFeed(
            CoverPolicy.onHomeFeedAfterRead(
                previous = onHomeFeed,
                screen = screen,
                selectedTab = selectedBottomTab,
                wholeWindow = wholeWindow,
                tabBarSeen = hasMainTabBar,
            ),
            "walk=$screen selTab=$selectedBottomTab src=${selection.sourceLabel} tabBar=$hasMainTabBar profile=${profileTracker.context}",
        )
        val decisionTrace = describeDecision(
            decision = decision,
            priorMode = priorMode,
            previousScreenBefore = previousScreenBefore,
            blockAttempt = blockAttempt,
        )

        recordEvent(
            event = event,
            screen = screen,
            action = action,
            signature = signature,
            diagnostics = "$diagnostics | $decisionTrace",
            treeSource = selection.sourceLabel,
            selectedBottomTab = selectedBottomTab,
            lastNonReelsBottomTab = lastNonReelsBottomTab,
            modeBefore = priorMode,
            previousScreenBefore = previousScreenBefore,
            previousScreenAfter = stateMachine.currentPrevScreen(),
            blockRoute = blockAttempt?.route ?: decision.reason.takeIf { !decision.fire },
            blockSucceeded = blockAttempt?.succeeded,
            rootPackageName = selection.rootPackageName,
            sourcePackageName = selection.sourcePackageName,
            nodeCount = treeDebug?.nodeCount,
            treeDepth = treeDebug?.maxDepth,
            interestingIds = treeDebug?.interestingIds ?: emptyList(),
            sampledDescriptions = treeDebug?.sampledDescriptions ?: emptyList(),
            handleLatencyMs = System.currentTimeMillis() - handleStart,
            fastPath = false,
        )
    }

    /**
     * Fast-path handler — we already know the screen type from the class
     * name; we still pull a signature when we're in DM-allowed mode so
     * reel-to-reel swipes are caught. Called for all four fast-path
     * screen types (REEL_VIEWER, REELS_TAB, DM_THREAD, DM_INBOX).
     */
    private fun handleClassifiedScreen(
        event: AccessibilityEvent,
        eventClassName: String?,
        screen: ScreenType,
        handleStart: Long,
        trustTap: Boolean = false,
    ) {
        var resolvedScreen = screen
        var signature: String? = null
        var selectedBottomTab: String? = null
        var treeSource = "none"
        var rootPackageName: String? = null
        var sourcePackageName: String? = null
        var treeDebug: ReelDetector.TreeDebug? = null
        var activeNode: AccessibilityNodeInfo? = null
        var openedFromDm = false

        // Only bother snapshotting when we actually need a signature —
        // i.e. a reel viewer while we might be in DM-allowed mode, or
        // when we need to know which bottom tab is selected.
        // A fresh Reels-tab tap needs none: the tree still shows the old
        // tab selected and would turn the block into a no-op, and reading
        // it costs a few hundred ms while Reels starts playing.
        val needsTree = screen == ScreenType.REEL_VIEWER ||
            (screen == ScreenType.REELS_TAB && !trustTap)
        if (needsTree) {
            val rootNode: AccessibilityNodeInfo? = try {
                rootInActiveWindow
            } catch (_: Throwable) {
                null
            }
            val sourceNode: AccessibilityNodeInfo? = try {
                event.source
            } catch (_: Throwable) {
                null
            }
            val windowNode = if (isInstagramPackage(rootNode?.packageName?.toString().orEmpty())) {
                null
            } else {
                focusedInstagramRoot()
            }
            val selection = selectInstagramNode(rootNode, sourceNode, windowNode)
            treeSource = selection.sourceLabel
            rootPackageName = selection.rootPackageName
            sourcePackageName = selection.sourcePackageName
            activeNode = selection.node
            selection.node?.toSnapshot()?.let { snap ->
                treeDebug = ReelDetector.inspectTree(snap)
                signature = ReelDetector.extractReelSignature(snap)
                selectedBottomTab = ReelDetector.extractSelectedBottomTab(snap)
                if (!selectedBottomTab.isNullOrBlank()) lastSelectedTab = selectedBottomTab
                if (!selectedBottomTab.isNullOrBlank() && selectedBottomTab != "reels") {
                    lastNonReelsBottomTab = selectedBottomTab
                }
                openedFromDm = screen == ScreenType.REEL_VIEWER && openedFromDm(snap)
                val treeScreen = ReelDetector.detectScreen(snap)
                if (treeScreen == ScreenType.DM_THREAD ||
                    treeScreen == ScreenType.DM_INBOX ||
                    (treeScreen == ScreenType.OTHER &&
                        !selectedBottomTab.isNullOrBlank() &&
                        selectedBottomTab != "reels")
                ) {
                    resolvedScreen = treeScreen
                }
            }
        }
        val stableNonDmSurface = resolvedScreen == ScreenType.OTHER &&
            !selectedBottomTab.isNullOrBlank() &&
            selectedBottomTab != "reels"

        val priorMode = stateMachine.currentMode()
        val previousScreenBefore = stateMachine.currentPrevScreen()
        if (resolvedScreen == ScreenType.REELS_TAB || resolvedScreen == ScreenType.REEL_VIEWER) {
            lastBlockedScreenEventAt = System.currentTimeMillis()
        }
        val dmReelSwipe = isDmReelSwipeScroll(event, resolvedScreen, priorMode)

        val action = if (dmReelSwipe) {
            stateMachine.onReelScroll()
        } else {
            val fromDm = openedFromDm && resolvedScreen == ScreenType.REEL_VIEWER
            stateMachine.handleScreen(
                resolvedScreen,
                signatureForStateMachine(resolvedScreen, signature, priorMode, fromDm),
                reelOrigin(),
                fromDm,
            )
        }
        if (stableNonDmSurface) {
            stateMachine.noteStableNonDmSurface()
        }
        if (priorMode == SessionStateMachine.Mode.BLOCKING &&
            stateMachine.currentMode() == SessionStateMachine.Mode.IDLE
        ) {
            clearBlockingCooldown()
            overlay.hideSoon()
        }
        updateDmAllowedWindow(priorMode, action)
        // Intentionally NOT calling clearBlockingCooldown() here on
        // stableNonDmSurface. It used to fire whenever home's tree
        // settled with the bottom nav visible, which is 100–300 ms
        // after a REELS_TAB block — so the cooldown that was supposed
        // to protect us from stale REELS_TAB events got wiped early,
        // and a stale event that arrived ~400 ms after the block
        // (Samsung's acessibility lag) would re-fire and mis-route
        // the next unrelated tap (DM icon, Explore, etc). Let the
        // 1500 ms cooldown expire naturally instead.
        val decision = decideBlock(
            action = action,
            screen = resolvedScreen,
            priorMode = priorMode,
            previousScreenBefore = previousScreenBefore,
        )
        val blockAttempt = if (decision.fire) {
            triggerBlock(screen = resolvedScreen, activeNode = activeNode)
        } else {
            null
        }
        // After the block, so a block's feed-cover hold is already on.
        if (resolvedScreen != ScreenType.OTHER) setOnHomeFeed(false, "fast=$resolvedScreen")
        val decisionTrace = describeDecision(
            decision = decision,
            priorMode = priorMode,
            previousScreenBefore = previousScreenBefore,
            blockAttempt = blockAttempt,
        )

        val diagnostics = buildString {
            append("FAST cls=")
            append(eventClassName ?: "null")
            append(" tree=")
            append(treeSource)
            append(" resolved=")
            append(resolvedScreen.name)
            append(" rootPkg=")
            append(rootPackageName ?: "null")
            append(" sourcePkg=")
            append(sourcePackageName ?: "null")
            if (!selectedBottomTab.isNullOrBlank()) {
                append(" selTab=")
                append(selectedBottomTab)
            }
            treeDebug?.let {
                append(" nodes=")
                append(it.nodeCount)
                append(" depth=")
                append(it.maxDepth)
                if (it.interestingIds.isNotEmpty()) {
                    append(" ids=")
                    append(it.interestingIds.joinToString(","))
                }
            }
            append(" | ")
            append(decisionTrace)
        }

        recordEvent(
            event = event,
            screen = resolvedScreen,
            action = action,
            signature = signature,
            diagnostics = diagnostics,
            treeSource = treeSource,
            selectedBottomTab = selectedBottomTab,
            lastNonReelsBottomTab = lastNonReelsBottomTab,
            modeBefore = priorMode,
            previousScreenBefore = previousScreenBefore,
            previousScreenAfter = stateMachine.currentPrevScreen(),
            blockRoute = blockAttempt?.route ?: decision.reason.takeIf { !decision.fire },
            blockSucceeded = blockAttempt?.succeeded,
            rootPackageName = rootPackageName,
            sourcePackageName = sourcePackageName,
            nodeCount = treeDebug?.nodeCount,
            treeDepth = treeDebug?.maxDepth,
            interestingIds = treeDebug?.interestingIds ?: emptyList(),
            sampledDescriptions = treeDebug?.sampledDescriptions ?: emptyList(),
            handleLatencyMs = System.currentTimeMillis() - handleStart,
            fastPath = true,
        )
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        try {
            settings.unregisterListener(settingsListener)
        } catch (_: Throwable) { /* ignore */ }
        debugDumpReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) { /* ignore */ }
        }
        debugDumpReceiver = null
        screenReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Throwable) { /* ignore */ }
        }
        screenReceiver = null
        displayListener?.let {
            try { getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(it) } catch (_: Throwable) { /* ignore */ }
        }
        displayListener = null
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        presenceWatchActive = false
        overlay.hide()
        feedCovers.clear()
        notifMgr.cancel(STATUS_NOTIF_ID)
        super.onDestroy()
    }

    /** Wipe all per-visit state — the user is no longer in Instagram. */
    private fun resetSessionState() {
        lastNonReelsBottomTab = null
        dmAllowedSinceMs = 0L
        dmReelBaseline = null
        earlyDmSwipeCoverAt = 0L
        lastBlockedScreenEventAt = 0L
        stateMachine.reset()
        clearBlockingCooldown()
        overlay.hide()
        endFeedHold()
        setOnHomeFeed(false)
    }

    private fun setOnHomeFeed(value: Boolean, why: String = "") {
        if (value != onHomeFeed) Log.d(TAG, "onHomeFeed=$value $why")
        onHomeFeed = value
        when {
            value -> scheduleFeedRefresh()
            !feedHold.isHeld(SystemClock.uptimeMillis()) -> feedCovers.clear()
        }
    }

    /**
     * The block cover, plus a hold on the Home-feed covers so the feed's
     * reels stay covered while the block bounces the user back to Home.
     */
    private fun showBlockCover() {
        if (igAway) return
        overlay.showBlocked()
        if (feedCovers.isShowing) {
            feedHold.start(SystemClock.uptimeMillis())
            handler.removeCallbacks(feedHoldEnd)
            handler.postDelayed(feedHoldEnd, CoverPolicy.FEED_HOLD_MS)
        }
        ensureCoverGuard()
    }

    private fun endFeedHold() {
        feedHold.release()
        handler.removeCallbacks(feedHoldEnd)
    }

    private fun scheduleFeedRefresh() {
        if (feedRefreshPosted) return
        feedRefreshPosted = true
        handler.post(feedRefreshRunnable)
    }

    /**
     * Covers every reel on screen in the Home feed. Four id lookups on
     * [ReadThreads.feed] instead of a tree walk, so the boxes keep up
     * with scrolling; one lookup in flight at a time.
     */
    private fun refreshFeedReelCovers() {
        feedRefreshPosted = false
        if (!feedCoversWanted()) return
        if (feedReadInFlight) {
            feedRefreshAgain = true
            return
        }
        feedReadInFlight = true
        ReadThreads.feed.post {
            val rects = try {
                readFeedReelRects()
            } catch (t: Throwable) {
                Log.w(TAG, "feed lookup failed", t)
                null
            }
            handler.post {
                feedReadInFlight = false
                if (destroyed) return@post
                // The user may have left Home or flipped a switch while
                // the lookup ran.
                if (rects != null && feedCoversWanted()) {
                    // Mid-bounce the tree can be half Reels, half Home:
                    // keep the boxes already up rather than flash the
                    // feed uncovered.
                    if (rects.isNotEmpty() || !feedHold.isHeld(SystemClock.uptimeMillis())) {
                        feedCovers.show(rects)
                        ensureCoverGuard()
                    }
                }
                if (feedRefreshAgain) {
                    feedRefreshAgain = false
                    scheduleFeedRefresh()
                }
            }
        }
    }

    /** False (after clearing what should go) when no feed boxes belong on screen. */
    private fun feedCoversWanted(): Boolean {
        if (!settings.isActive() || !settings.blockHomeReels || igAway) {
            endFeedHold()
            feedCovers.clear()
            return false
        }
        if (!onHomeFeed) {
            if (!feedHold.isHeld(SystemClock.uptimeMillis())) feedCovers.clear()
            return false
        }
        return true
    }

    /** Runs on [ReadThreads.feed]. Null when Instagram's tree is not readable. */
    private fun readFeedReelRects(): List<ScreenRect>? {
        val root = currentInstagramRoot() ?: return null
        val pkg = root.packageName?.toString() ?: return null
        val t0 = System.currentTimeMillis()
        val nodes = listOf(
            ReelDetector.FeedNodeKind.MEDIA_GROUP to ReelDetector.FEED_MEDIA_GROUP_ID,
            ReelDetector.FeedNodeKind.MEDIA_IMAGE to ReelDetector.FEED_MEDIA_IMAGE_ID,
            ReelDetector.FeedNodeKind.HEADER to ReelDetector.FEED_HEADER_ID,
            ReelDetector.FeedNodeKind.REEL_CTA to ReelDetector.FEED_REEL_CTA_ID,
        ).flatMap { (kind, id) ->
            val hits = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/$id")
            } catch (_: Throwable) {
                emptyList()
            }
            hits.map { n ->
                val r = Rect().also { n.getBoundsInScreen(it) }
                ReelDetector.FeedNode(
                    kind = kind,
                    description = n.contentDescription?.toString(),
                    rect = ScreenRect(r.left, r.top, r.right, r.bottom),
                    visible = n.isVisibleToUser,
                )
            }
        }
        val rects = ReelDetector.feedReelRects(nodes)
        if (rects.size != feedCoverCountLogged) {
            feedCoverCountLogged = rects.size
            Log.d(TAG, "feed reels=${rects.size} nodes=${nodes.size} lookup=${System.currentTimeMillis() - t0}ms")
        }
        return rects
    }

    private fun startPresenceWatch() {
        if (presenceWatchActive) return
        presenceWatchActive = true
        presence.onWatchStart()
        handler.postDelayed(presenceCheck, PRESENCE_POLL_MS)
    }

    private fun checkInstagramPresence() {
        if (!presenceWatchActive) return
        val windows = currentWindowSummaries()
        val verdict = presence.classify(windows)
        when (presence.onCheck(verdict)) {
            InstagramPresence.Decision.KEEP_WATCHING ->
                handler.postDelayed(presenceCheck, PRESENCE_POLL_MS)
            InstagramPresence.Decision.STOP -> presenceWatchActive = false
            InstagramPresence.Decision.LEFT -> {
                presenceWatchActive = false
                val modeBefore = stateMachine.currentMode()
                val prevBefore = stateMachine.currentPrevScreen()
                resetSessionState()
                val apps = windows.filter { it.isApplication }.joinToString(",") { it.packageName ?: "?" }
                Log.i(TAG, "leftInstagram modeBefore=${modeBefore.name} prevBefore=${prevBefore.name} apps=$apps")
                DetectionLog.record(
                    DetectionLog.Entry(
                        timestamp = System.currentTimeMillis(),
                        screen = ScreenType.UNKNOWN,
                        action = SessionStateMachine.Action.NONE,
                        mode = stateMachine.currentMode(),
                        modeBefore = modeBefore,
                        previousScreenBefore = prevBefore,
                        previousScreenAfter = stateMachine.currentPrevScreen(),
                        signature = null,
                        note = "leftInstagram",
                        diagnostics = "leftInstagram: state reset (foreground apps=$apps)",
                    )
                )
            }
        }
    }

    // ---- Cover guard: every cover comes down the moment Instagram leaves the front ----

    /** Remembers which windows are Instagram's, so most checks need no tree read. */
    private fun noteInstagramWindow(windowId: Int) {
        if (windowId < 0) return
        igWindowIds.remove(windowId)
        igWindowIds.add(windowId)
        while (igWindowIds.size > IG_WINDOW_MEMORY) igWindowIds.remove(igWindowIds.first())
    }

    /** Keeps checking the front window while a cover is up, or while Instagram sits behind the shade. */
    private fun ensureCoverGuard() {
        if (guardTickPosted || destroyed || screenOff) return
        val wanted = feedCovers.isShowing || overlay.isShowing || (igAway && igVisibleBehind)
        if (!wanted) return
        guardTickPosted = true
        handler.postDelayed(guardTick, COVER_GUARD_POLL_MS)
    }

    /**
     * Looks at which window is in front, on [ReadThreads.guard]. One
     * window-list call; a tree read only for a window not seen before.
     */
    private fun requestFrontCheck(throttled: Boolean = false) {
        if (destroyed || frontCheckInFlight) return
        val now = SystemClock.uptimeMillis()
        if (throttled && now - lastFrontCheckAt < FRONT_CHECK_MIN_GAP_MS) return
        lastFrontCheckAt = now
        frontCheckInFlight = true
        val known = igWindowIds.toSet()
        val awayId = lastAwayWindowId
        ReadThreads.guard.post {
            var verdict = FrontWindow.Verdict.UNKNOWN
            var frontId = -1
            var igVisible = false
            try {
                val list = windows
                val wins = list.map { w ->
                    FrontWindow.Win(
                        id = w.id,
                        kind = when (w.type) {
                            AccessibilityWindowInfo.TYPE_APPLICATION -> FrontWindow.Kind.APP
                            AccessibilityWindowInfo.TYPE_SYSTEM -> FrontWindow.Kind.SYSTEM
                            else -> FrontWindow.Kind.IGNORED
                        },
                        focused = w.isFocused,
                        active = w.isActive,
                    )
                }
                igVisible = wins.any { it.id in known }
                val front = FrontWindow.front(wins)
                frontId = front?.id ?: -1
                verdict = FrontWindow.judge(front) { win ->
                    when (win.id) {
                        in known -> true
                        awayId -> false
                        else -> list.firstOrNull { it.id == win.id }
                            ?.let { w -> try { w.root?.packageName?.toString() } catch (_: Throwable) { null } }
                            ?.let { isInstagramPackage(it) }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "front check failed", t)
            }
            handler.post {
                frontCheckInFlight = false
                if (destroyed) return@post
                applyFrontVerdict(verdict, frontId, igVisible)
            }
        }
    }

    private fun applyFrontVerdict(verdict: FrontWindow.Verdict, frontId: Int, igVisible: Boolean = true) {
        when (verdict) {
            FrontWindow.Verdict.AWAY -> {
                if (frontId >= 0) lastAwayWindowId = frontId
                igVisibleBehind = igVisible
                if (!igAway) {
                    igAway = true
                    Log.d(TAG, "guard: Instagram not in front (window $frontId), covers down")
                }
                overlay.hide()
                endFeedHold()
                feedCovers.clear()
            }
            FrontWindow.Verdict.INSTAGRAM -> {
                noteInstagramWindow(frontId)
                if (igAway && !screenOff) {
                    igAway = false
                    Log.d(TAG, "guard: Instagram back in front")
                    if (onHomeFeed) scheduleFeedRefresh()
                    // Events that came while away were dropped; look again.
                    awayDroppedEvent?.let { enqueueTreeWalk(it) }
                }
                awayDroppedEvent = null
            }
            FrontWindow.Verdict.UNKNOWN -> Unit
        }
        ensureCoverGuard()
    }

    private fun onScreenOff(source: String) {
        if (screenOff) return
        Log.d(TAG, "screen off ($source), covers down")
        screenOff = true
        applyFrontVerdict(FrontWindow.Verdict.AWAY, -1, igVisible = false)
    }

    private fun onScreenOn() {
        screenOff = false
        requestFrontCheck()
    }

    /**
     * Samsung hands ACTION_SCREEN_OFF to apps over a second after the
     * power press, so a quick wake could show the covers on the lock
     * screen. The display's own state changes the moment it turns off.
     * It also changes on refresh-rate switches, so only an on/off
     * transition does anything.
     */
    private fun registerDisplayListener() {
        if (displayListener != null) return
        val dm = getSystemService(DisplayManager::class.java) ?: return
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || destroyed) return
                val state = dm.getDisplay(displayId)?.state ?: return
                val on = state == Display.STATE_ON || state == Display.STATE_ON_SUSPEND || state == Display.STATE_VR
                if (!on) onScreenOff("display") else if (screenOff) onScreenOn()
            }
        }
        dm.registerDisplayListener(listener, handler)
        displayListener = listener
    }

    private fun registerScreenReceiver() {
        registerDisplayListener()
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff("broadcast")
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun currentWindowSummaries(): List<InstagramPresence.WindowSummary> {
        val list = try { windows } catch (_: Throwable) { null } ?: return emptyList()
        return list.map { w ->
            val pkg = try { w.root?.packageName?.toString() } catch (_: Throwable) { null }
            InstagramPresence.WindowSummary(
                isApplication = w.type == AccessibilityWindowInfo.TYPE_APPLICATION,
                packageName = pkg,
            )
        }
    }

    private fun registerDebugDumpReceiver() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        if (debugDumpReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val label = intent.getStringExtra("label") ?: "manual"
                DebugTreeDump.dump(this@ReelBlockerService, label)
            }
        }
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(DebugTreeDump.ACTION), ContextCompat.RECEIVER_EXPORTED,
        )
        debugDumpReceiver = receiver
    }

    private fun recordEvent(
        event: AccessibilityEvent,
        screen: ScreenType,
        action: SessionStateMachine.Action,
        signature: String?,
        diagnostics: String,
        treeSource: String?,
        selectedBottomTab: String?,
        lastNonReelsBottomTab: String? = null,
        modeBefore: SessionStateMachine.Mode? = null,
        previousScreenBefore: ScreenType? = null,
        previousScreenAfter: ScreenType? = null,
        blockRoute: String? = null,
        blockSucceeded: Boolean? = null,
        rootPackageName: String? = null,
        sourcePackageName: String? = null,
        nodeCount: Int? = null,
        treeDepth: Int? = null,
        interestingIds: List<String> = emptyList(),
        sampledDescriptions: List<String> = emptyList(),
        handleLatencyMs: Long?,
        fastPath: Boolean,
    ) {
        val eventTypeName = describeEvent(event.eventType)

        // Collapse identical repeats: while a reel plays on a blocked
        // screen, Instagram fires the same content-changed event ~50×/s
        // and every one produced an identical "alreadyBlocking" entry —
        // 115 of the 200 ring-buffer slots in one field log. Keep one
        // entry per second per (screen, action, route) and fold the
        // count into the next distinct entry.
        val now = System.currentTimeMillis()
        val recordKey = "${screen.name}|${action.name}|${blockRoute.orEmpty()}"
        if (recordKey == lastRecordKey && now - lastRecordKeyAt < REPEAT_LOG_INTERVAL_MS) {
            suppressedRepeats += 1
            return
        }
        val repeatNote = if (suppressedRepeats > 0) " (+$suppressedRepeats similar suppressed)" else ""
        lastRecordKey = recordKey
        lastRecordKeyAt = now
        suppressedRepeats = 0

        // Mirror a compact line to logcat so a connected `adb logcat -s
        // ReelBlock/Service` can watch classification live during a
        // debug session, without exporting from the app.
        val scrollInfo = if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            " idx=${event.fromIndex}/${event.toIndex}/${event.itemCount} base=${dmReelBaseline?.index} " +
                "src=${event.className?.toString()?.substringAfterLast('.')}"
        } else ""
        Log.d(
            TAG,
            "$eventTypeName scr=${screen.name} act=${action.name} " +
                "mode=${stateMachine.currentMode().name} selTab=${selectedBottomTab ?: "-"} " +
                "sig=${signature ?: "-"} route=${blockRoute ?: "-"} " +
                "lat=${handleLatencyMs ?: -1}ms age=${android.os.SystemClock.uptimeMillis() - event.eventTime}ms " +
                "nodes=${nodeCount ?: -1}$scrollInfo$repeatNote",
        )

        DetectionLog.record(
            DetectionLog.Entry(
                timestamp = System.currentTimeMillis(),
                screen = screen,
                action = action,
                mode = stateMachine.currentMode(),
                modeBefore = modeBefore,
                previousScreenBefore = previousScreenBefore,
                previousScreenAfter = previousScreenAfter,
                signature = signature,
                note = eventTypeName,
                diagnostics = diagnostics + repeatNote,
                eventType = eventTypeName,
                eventClassName = event.className?.toString(),
                packageName = event.packageName?.toString(),
                treeSource = treeSource,
                selectedBottomTab = selectedBottomTab,
                lastNonReelsBottomTab = lastNonReelsBottomTab,
                blockRoute = blockRoute,
                blockSucceeded = blockSucceeded,
                rootPackageName = rootPackageName,
                sourcePackageName = sourcePackageName,
                nodeCount = nodeCount,
                treeDepth = treeDepth,
                interestingIds = interestingIds,
                sampledDescriptions = sampledDescriptions,
                handleLatencyMs = handleLatencyMs,
                fastPath = fastPath,
            )
        )
    }

    private fun triggerBlock(
        screen: ScreenType,
        activeNode: AccessibilityNodeInfo?,
    ): BlockAttempt {
        // A block owns the cover from here; an early DM-swipe cover is no
        // longer ours to drop.
        earlyDmSwipeCoverAt = 0L
        val now = System.currentTimeMillis()
        if (screen == lastBlockScreen && now - lastBlockAt < BLOCK_COOLDOWN_MS) {
            val remainingMs = BLOCK_COOLDOWN_MS - (now - lastBlockAt)
            return BlockAttempt(
                route = "cooldown:${screen.name}",
                succeeded = false,
                skipReason = "cooldownRemainingMs=$remainingMs",
            )
        }
        lastBlockAt = now
        lastBlockScreen = screen
        lastBlockActionAt = SystemClock.uptimeMillis()

        showBlockCover()

        // Track how many physical block actions have fired for the same
        // screen without an intervening screen transition. If this ceiling
        // is crossed the circuit breaker in [decideBlock] will silence
        // further actions until the screen genuinely changes — the
        // historical failure was Samsung's bottom-tab-bar `isSelected`
        // state lingering on "reels" after we had already navigated the
        // user to Home, which would otherwise queue BACK presses until
        // the user was ejected from Instagram.
        if (lastFiredBlockScreen == screen) {
            consecutiveBlockActions += 1
        } else {
            consecutiveBlockActions = 1
        }
        lastFiredBlockScreen = screen

        if (screen == ScreenType.REELS_TAB) {
            val targetTab = lastNonReelsBottomTab ?: DEFAULT_REELS_FALLBACK_TAB
            // Prefer a FRESH root over the event-time node: a Galaxy S24
            // log showed the home-tab click failing on every block
            // (`clickFailed->back`) because the event's node tree had
            // already been recycled by the time we clicked.
            val navigationNode = currentInstagramRoot() ?: activeNode
            val clicked = clickBottomTab(navigationNode, targetTab)
            if (!clicked) {
                // Tab-bar not reachable / click rejected — fall back to a
                // single BACK to pop the in-flight Reels navigation.
                handler.postDelayed({
                    if (stateMachine.currentMode() == SessionStateMachine.Mode.BLOCKING &&
                        blockedScreenEventFresh() && !igAway
                    ) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }, BACK_FRAME_DELAY_MS)
            }
            // Either way, verify the navigation actually landed and retry
            // if not — one-shot navigation proved fragile in the field
            // (the user sat scrolling the Reels tab for 10 s while every
            // event was deduped as "alreadyBlocking").
            scheduleNavVerification(attempt = 1)
            val failureTag = if (clicked) "" else {
                if (navigationNode == null) ":noNode->back" else ":clickFailed->back"
            }
            return BlockAttempt(
                route = "tapTab:$targetTab$failureTag",
                succeeded = true,
            )
        }

        // REEL_VIEWER path — single BACK. Fire on the next main-loop
        // tick (~one frame, ~16 ms) so the overlay's slide-in animation
        // gets a head start and the user perceives banner + nav as one
        // coordinated action. Guard with a mode check — if the state
        // machine has already exited BLOCKING (e.g. a stale OTHER event
        // reset us), don't fire a phantom BACK. The verification loop
        // below retries if the viewer is still on top afterwards.
        handler.postDelayed({
            if (stateMachine.currentMode() == SessionStateMachine.Mode.BLOCKING &&
                blockedScreenEventFresh() && !igAway
            ) {
                performGlobalAction(GLOBAL_ACTION_BACK)
            }
        }, BACK_FRAME_DELAY_MS)
        scheduleNavVerification(attempt = 1)
        return BlockAttempt(route = "back", succeeded = true)
    }

    /**
     * Post-block watchdog: check ~700 ms after each navigation attempt
     * whether we actually left the blocked screen, and try again if not.
     *
     * Every attempt re-reads a fresh root and re-classifies, so it
     * self-corrects regardless of WHICH single action flaked (stale
     * nodes, rejected click, swallowed BACK). It stops the moment the
     * screen is no longer a blocked one, the state machine has left
     * BLOCKING, blocking is disabled, or Instagram is demonstrably not
     * the foreground app.
     */
    private fun scheduleNavVerification(attempt: Int) {
        if (attempt > MAX_NAV_ATTEMPTS) return
        handler.postDelayed({ verifyNavigation(attempt) }, NAV_VERIFY_DELAY_MS)
    }

    private fun verifyNavigation(attempt: Int) {
        if (!settings.isActive() || igAway) return
        if (stateMachine.currentMode() != SessionStateMachine.Mode.BLOCKING) return
        val root = currentInstagramRoot()
        val screen: ScreenType
        val route: String
        if (root == null) {
            // No usable Instagram root. That does NOT mean the user left:
            // Samsung reports a null/launcher root while Instagram is
            // foreground and streaming events (field-verified — the
            // previous root-trusting version died here and left the user
            // scrolling the Reels tab for 45 s). If blocked-screen events
            // are still arriving, the user is demonstrably still on the
            // reel surface — press BACK blind. If they aren't, do
            // nothing this round but keep checking until the ceiling.
            if (!blockedScreenEventFresh()) {
                if (attempt < MAX_NAV_ATTEMPTS) scheduleNavVerification(attempt + 1)
                return
            }
            performGlobalAction(GLOBAL_ACTION_BACK)
            screen = lastBlockScreen ?: ScreenType.REEL_VIEWER
            route = "retryBlindBack"
        } else {
            val rootSnapshot = root.toSnapshot()
            val readSnapshot = if (ReelDetector.isPeekMenu(rootSnapshot)) {
                peekBehindMenu()?.second ?: rootSnapshot
            } else rootSnapshot
            screen = ReelDetector.detectScreen(readSnapshot)
            if (screen != ScreenType.REELS_TAB && screen != ScreenType.REEL_VIEWER) {
                // Navigation landed. Let the normal event flow reset the
                // mode; the cover can come down now.
                overlay.hideSoon()
                return
            }
            if (screen == ScreenType.REELS_TAB) {
                val targetTab = lastNonReelsBottomTab ?: DEFAULT_REELS_FALLBACK_TAB
                route = if (clickBottomTab(root, targetTab)) {
                    "retryTapTab:$targetTab"
                } else {
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    "retryTapTab:$targetTab:clickFailed->back"
                }
            } else {
                performGlobalAction(GLOBAL_ACTION_BACK)
                route = "retryBack"
            }
        }
        lastBlockAt = System.currentTimeMillis()
        lastBlockActionAt = SystemClock.uptimeMillis()
        // Still on the blocked screen: keep the reel covered while we retry.
        showBlockCover()
        Log.i(TAG, "navRetry attempt=$attempt screen=${screen.name} route=$route")
        DetectionLog.record(
            DetectionLog.Entry(
                timestamp = System.currentTimeMillis(),
                screen = screen,
                action = SessionStateMachine.Action.BLOCK_REEL,
                mode = stateMachine.currentMode(),
                signature = null,
                note = "navRetry",
                diagnostics = "navRetry attempt=$attempt route=$route",
                blockRoute = route,
                blockSucceeded = true,
            )
        )
        scheduleNavVerification(attempt + 1)
    }

    /**
     * True while events proving "the user is looking at a blockable
     * Instagram screen" are still arriving. This gates every blind BACK
     * press instead of `rootInActiveWindow`-based checks: a field log
     * showed Samsung reporting a null/launcher root while Instagram was
     * demonstrably foreground (its reel events kept streaming), which
     * silently killed the previous root-guarded navigation — the user
     * sat scrolling the Reels tab for 45 s. The event signal cannot
     * false-positive from a backgrounded Instagram: a covered app's
     * tree is unreachable, so nothing classifies as REELS_TAB /
     * REEL_VIEWER.
     */
    private fun blockedScreenEventFresh(): Boolean =
        System.currentTimeMillis() - lastBlockedScreenEventAt < BLOCKED_EVENT_FRESH_MS

    /**
     * Decide whether a TYPE_VIEW_SCROLLED event on an allowed DM reel is
     * a real swipe to the next reel.
     *
     * Uses the pager page index the scroll carries; see [DmReelSwipe].
     * A scroll with no index (the comments sheet, the caption) is never
     * a swipe. A swipe that somehow carries no index is still caught by
     * the signature compare in the state machine.
     */
    private fun isDmReelSwipeScroll(
        event: AccessibilityEvent,
        screen: ScreenType,
        priorMode: SessionStateMachine.Mode,
    ): Boolean {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return false
        if (screen != ScreenType.REEL_VIEWER) return false
        if (priorMode != SessionStateMachine.Mode.DM_ALLOWED_REEL) return false
        if (dmAllowedSinceMs <= 0L) return false
        val verdict = DmReelSwipe.judge(dmReelBaseline, scrollPageOf(event))
        dmReelBaseline = verdict.baseline
        return verdict.isSwipe
    }

    private fun scrollPageOf(event: AccessibilityEvent): DmReelSwipe.Page? =
        DmReelSwipe.pageOf(event.fromIndex, event.toIndex, event.className)

    /**
     * The signature handed to the state machine is withheld while the
     * reel viewer is still settling: on entry (and for the scroll-grace
     * window after a DM-allow) the tree is a hybrid of the old surface
     * and the mounting reel, and the extractor can grab the wrong
     * author — a field log showed an ad's "squarespace" latched as the
     * allowed reel's signature, making the actual reel look like a
     * swipe and kicking the user out of a legitimately opened DM reel
     * on first tap. Once the grace elapses, signatures flow through and
     * the swipe-compare backstop arms.
     */
    private fun signatureForStateMachine(
        screen: ScreenType,
        signature: String?,
        priorMode: SessionStateMachine.Mode,
        openedFromDm: Boolean = false,
    ): String? {
        if (screen != ScreenType.REEL_VIEWER || signature == null) return signature
        val dmEntryImminent = priorMode == SessionStateMachine.Mode.IDLE &&
            stateMachine.dmEntryImminent(openedFromDm)
        val inDmGrace = priorMode == SessionStateMachine.Mode.DM_ALLOWED_REEL &&
            dmAllowedSinceMs > 0L &&
            System.currentTimeMillis() - dmAllowedSinceMs < DM_REEL_SCROLL_GRACE_MS
        return if (dmEntryImminent || inDmGrace) null else signature
    }

    /**
     * Compose a one-line audit trail for every event we handle: why we
     * did or didn't act, what the cooldown and mode looked like, and
     * which route we took if we acted. This is the primary field a
     * person reads when diagnosing "it's blocking stuff it
     * shouldn't" or "reels aren't getting blocked" — we want enough
     * context in one line to decide without having to correlate across
     * multiple entries.
     */
    private fun describeDecision(
        decision: BlockDecision,
        priorMode: SessionStateMachine.Mode,
        previousScreenBefore: ScreenType,
        blockAttempt: BlockAttempt?,
    ): String = buildString {
        append("decide=")
        append(decision.reason)
        append(" priorMode=")
        append(priorMode.name)
        append(" prevScreen=")
        append(previousScreenBefore.name)
        append(" consecBlocks=")
        append(consecutiveBlockActions)
        if (lastFiredBlockScreen != null) {
            append(" lastFired=")
            append(lastFiredBlockScreen?.name)
        }
        if (dmAllowedSinceMs > 0L) {
            append(" dmAllowedAgeMs=")
            append(System.currentTimeMillis() - dmAllowedSinceMs)
        }
        if (blockAttempt != null) {
            append(" route=")
            append(blockAttempt.route)
            append(" ok=")
            append(blockAttempt.succeeded)
            blockAttempt.skipReason?.let {
                append(" skip=")
                append(it)
            }
        }
    }

    private fun isInstagramPackage(pkg: String): Boolean = pkg in INSTAGRAM_PACKAGES

    /**
     * The reel on screen came from a DM: its reply bar is showing, or the
     * Messages tab was the last tab and the user is not on someone's
     * profile (a profile opened from a chat keeps that tab selected).
     */
    private fun openedFromDm(snapshot: NodeSnapshot?): Boolean =
        ReelDetector.hasDmReplyBar(snapshot) ||
            (lastSelectedTab == ReelDetector.TAB_DIRECT &&
                profileTracker.context != ProfileContextTracker.Context.OTHER_PROFILE)

    /** Where the reel on screen was opened from, for the per-place switches. */
    private fun reelOrigin(): SessionStateMachine.ReelOrigin = when (profileTracker.context) {
        ProfileContextTracker.Context.OWN_PROFILE -> SessionStateMachine.ReelOrigin.OWN_PROFILE
        ProfileContextTracker.Context.OTHER_PROFILE -> SessionStateMachine.ReelOrigin.ELSEWHERE
        ProfileContextTracker.Context.NONE -> when (lastSelectedTab) {
            ReelDetector.TAB_REELS -> SessionStateMachine.ReelOrigin.REELS_TAB
            ReelDetector.TAB_HOME -> SessionStateMachine.ReelOrigin.HOME
            ReelDetector.TAB_SEARCH -> SessionStateMachine.ReelOrigin.EXPLORE
            else -> SessionStateMachine.ReelOrigin.ELSEWHERE
        }
    }

    /**
     * Instagram's window when it has focus, read whole. Samsung sometimes
     * reports another window as active while Instagram is in front.
     */
    private fun focusedInstagramRoot(): AccessibilityNodeInfo? {
        val all = try { windows } catch (_: Throwable) { null } ?: return null
        val wins = all.map { w ->
            FrontWindow.Win(
                id = w.id,
                kind = if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    FrontWindow.Kind.APP
                } else if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM) {
                    FrontWindow.Kind.SYSTEM
                } else {
                    FrontWindow.Kind.IGNORED
                },
                focused = w.isFocused,
                active = w.isActive,
            )
        }
        val front = FrontWindow.front(wins) ?: return null
        if (front.kind != FrontWindow.Kind.APP) return null
        val window = all.firstOrNull { it.id == front.id } ?: return null
        val root = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                window.getRoot(WALK_PREFETCH)
            } else {
                window.root
            }
        } catch (_: Throwable) {
            null
        } ?: return null
        return if (isInstagramPackage(root.packageName?.toString().orEmpty())) root else null
    }

    /**
     * The tree to classify. [windowNode] is Instagram's focused window,
     * read when the active-window root isn't Instagram's; it is a whole
     * window, so it beats a read of the event's own list.
     */
    private fun selectInstagramNode(
        rootNode: AccessibilityNodeInfo?,
        sourceNode: AccessibilityNodeInfo?,
        windowNode: AccessibilityNodeInfo? = null,
    ): NodeSelection {
        val rootPackageName = rootNode?.packageName?.toString()
        val sourcePackageName = sourceNode?.packageName?.toString()
        return when {
            rootNode != null && isInstagramPackage(rootPackageName.orEmpty()) ->
                NodeSelection(rootNode, "root", rootPackageName, sourcePackageName)
            windowNode != null ->
                NodeSelection(windowNode, "window", rootPackageName, sourcePackageName)
            sourceNode != null && isInstagramPackage(sourcePackageName.orEmpty()) ->
                NodeSelection(sourceNode, "source", rootPackageName, sourcePackageName)
            else ->
                NodeSelection(null, "none", rootPackageName, sourcePackageName)
        }
    }

    /**
     * The long-press preview in Instagram's main window, read while its
     * menu (a popup window) has focus. Null when there is none.
     */
    private fun peekBehindMenu(): Pair<AccessibilityNodeInfo, NodeSnapshot>? {
        val all = try { windows } catch (_: Throwable) { null } ?: return null
        for (window in all) {
            if (window.isActive || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val root = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    window.getRoot(WALK_PREFETCH)
                } else {
                    window.root
                }
            } catch (_: Throwable) {
                null
            } ?: continue
            val pkg = root.packageName?.toString().orEmpty()
            if (!isInstagramPackage(pkg)) continue
            val peek = try {
                root.findAccessibilityNodeInfosByViewId("$pkg:id/${ReelDetector.PEEK_CONTAINER_ID}")
            } catch (_: Throwable) {
                null
            }?.firstOrNull { it.isVisibleToUser } ?: continue
            val snapshot = peek.toSnapshot() ?: continue
            return peek to snapshot
        }
        return null
    }

    private fun currentInstagramRoot(): AccessibilityNodeInfo? {
        val rootNode = try {
            rootInActiveWindow
        } catch (_: Throwable) {
            null
        } ?: return null
        val pkg = rootNode.packageName?.toString()
        return if (isInstagramPackage(pkg.orEmpty())) rootNode else null
    }

    /**
     * Decide whether this BLOCK_REEL action should be physically acted on
     * (press BACK / click Home tab) or whether it is a stale follow-up
     * that we should only log.
     *
     * The previous implementation had a REELS_TAB special case that
     * bypassed the "already blocking same screen" guard. On Samsung the
     * bottom-tab bar's `isSelected=reels` signal lingers for 1–2 seconds
     * after BACK has already landed the user on Home, so every one of
     * those stale REELS_TAB events would pass this check and queue
     * another BACK — cumulatively ejecting the user from Instagram. We
     * now treat all blocking screens uniformly: fire once per BLOCKING
     * session, rely on [consecutiveBlockActions] + cooldown to catch any
     * repeats that slip through.
     */
    private fun decideBlock(
        action: SessionStateMachine.Action,
        screen: ScreenType,
        priorMode: SessionStateMachine.Mode,
        previousScreenBefore: ScreenType,
    ): BlockDecision {
        if (action != SessionStateMachine.Action.BLOCK_REEL) {
            return BlockDecision(false, "action=${action.name}")
        }
        if (priorMode == SessionStateMachine.Mode.BLOCKING &&
            previousScreenBefore == screen
        ) {
            // Dedupe repeats of the same blocked screen — but only for a
            // bounded window. If blocked-screen events are STILL flowing
            // [BLOCK_REFIRE_MS] after the last physical action, the
            // navigation demonstrably didn't land (field log: user
            // scrolled the Reels tab for 45 s behind this guard) — fire
            // again. Stale after-navigation events can't re-trigger
            // here: they die out within 1–2 s of a successful escape.
            val sinceLastAction = System.currentTimeMillis() - lastBlockAt
            if (sinceLastAction < BLOCK_REFIRE_MS) {
                return BlockDecision(
                    false,
                    "alreadyBlocking:${screen.name}:prev=${previousScreenBefore.name}",
                )
            }
            return BlockDecision(
                true,
                "refire:${screen.name}:stuckForMs=$sinceLastAction",
            )
        }
        if (consecutiveBlockActions >= MAX_CONSECUTIVE_BLOCKS &&
            lastFiredBlockScreen == screen
        ) {
            return BlockDecision(
                false,
                "circuitBreaker:consecutive=$consecutiveBlockActions:screen=${screen.name}",
            )
        }
        return BlockDecision(true, "fire:${screen.name}:priorMode=${priorMode.name}")
    }

    private fun clearBlockingCooldown() {
        lastBlockAt = 0L
        lastBlockScreen = null
        consecutiveBlockActions = 0
        lastFiredBlockScreen = null
    }

    private fun updateDmAllowedWindow(
        priorMode: SessionStateMachine.Mode,
        action: SessionStateMachine.Action,
    ) {
        if (action == SessionStateMachine.Action.ALLOW_REEL &&
            stateMachine.currentMode() == SessionStateMachine.Mode.DM_ALLOWED_REEL &&
            priorMode != SessionStateMachine.Mode.DM_ALLOWED_REEL
        ) {
            dmAllowedSinceMs = System.currentTimeMillis()
            dmReelBaseline = null
            return
        }
        if (stateMachine.currentMode() != SessionStateMachine.Mode.DM_ALLOWED_REEL) {
            dmAllowedSinceMs = 0L
            dmReelBaseline = null
        }
    }

    private fun describeEvent(type: Int): String = when (type) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "windowStateChanged"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "windowContentChanged"
        AccessibilityEvent.TYPE_VIEW_SCROLLED -> "viewScrolled"
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windowsChanged"
        AccessibilityEvent.TYPE_VIEW_SELECTED -> "viewSelected"
        AccessibilityEvent.TYPE_VIEW_CLICKED -> "viewClicked"
        else -> "event($type)"
    }

    private fun clickBottomTab(
        node: AccessibilityNodeInfo?,
        tabLabel: String,
    ): Boolean {
        if (node == null) return false
        // Only ever click inside the bottom tab bar's subtree. Two field
        // incidents mandate this strictness:
        //  - Searching the whole tree for a "home…" description can
        //    match content chips ("home decor") or the top search bar.
        //  - A coordinate/gesture tap at remembered tab coordinates
        //    fired against a STALE tree taps whatever is really on
        //    screen — it opened the DM camera and the reels comment
        //    sheet for the user. Node-scoped ACTION_CLICK is the only
        //    safe click: a stale node's click fails instead of
        //    hitting the wrong control, and BACK handles the rest.
        val tabNode = findTabByViewId(node, tabLabel) ?: run {
            val tabBar = findMainTabBar(node) ?: return false
            findBottomTabNode(tabBar, tabLabel)
        } ?: return false
        // Note before clicking: the echo event can be queued while the
        // click call is still returning.
        selfTabClickEcho.noteSelfClick(tabLabel, System.currentTimeMillis())
        val clicked = performClick(tabNode)
        if (!clicked) selfTabClickEcho.clear()
        return clicked
    }

    /**
     * Direct id lookup of the tab button (`<pkg>:id/feed_tab`), checked to
     * sit inside the visible tab bar. One IPC round trip, where walking the
     * tree to the bar cost ~600 ms on a phone (Instagram keeps every
     * off-screen tab page in the tree).
     */
    private fun findTabByViewId(root: AccessibilityNodeInfo, tabLabel: String): AccessibilityNodeInfo? {
        val pkg = root.packageName?.toString() ?: return null
        val seg = ReelDetector.tabViewIdSegment(tabLabel) ?: return null
        val hits = try {
            root.findAccessibilityNodeInfosByViewId("$pkg:id/$seg")
        } catch (_: Throwable) {
            return null
        }
        return hits.firstOrNull { it.isVisibleToUser && isInsideTabBar(it) }
    }

    private fun isInsideTabBar(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = try { node.parent } catch (_: Throwable) { null }
        repeat(6) {
            val n = current ?: return false
            if (ReelDetector.isTabBarId(n.viewIdResourceName)) return true
            current = try { n.parent } catch (_: Throwable) { null }
        }
        return false
    }

    private fun findMainTabBar(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val pkg = node.packageName?.toString()
        if (pkg != null) {
            for (seg in ReelDetector.TAB_BAR_ID_LOOKUP) {
                val hit = try {
                    node.findAccessibilityNodeInfosByViewId("$pkg:id/$seg")
                } catch (_: Throwable) {
                    emptyList()
                }.firstOrNull { it.isVisibleToUser }
                if (hit != null) return hit
            }
        }
        return findMainTabBarWalk(node)
    }

    private fun findMainTabBarWalk(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (ReelDetector.isTabBarId(node.viewIdResourceName) && node.isVisibleToUser) {
            return node
        }
        if (!node.isVisibleToUser) return null
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val match = findMainTabBarWalk(child)
            if (match != null) return match
        }
        return null
    }

    /** The tab button for [tabLabel] inside the tab bar: by id first
     *  (`feed_tab`, `direct_tab`, ...), else by description. */
    private fun findBottomTabNode(
        node: AccessibilityNodeInfo,
        tabLabel: String,
    ): AccessibilityNodeInfo? {
        val tab = ReelDetector.tabLabelFromViewId(node.viewIdResourceName)
            ?: ReelDetector.tabLabelFromDescription(node.contentDescription)
        if (tab == tabLabel) return node

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val match = findBottomTabNode(child, tabLabel)
            if (match != null) return match
        }
        return null
    }

    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val target = current ?: return false
            if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            current = try {
                target.parent
            } catch (_: Throwable) {
                null
            }
        }
        return false
    }

    private fun configureRuntimeServiceInfo() {
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes =
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SELECTED or
                AccessibilityEvent.TYPE_VIEW_CLICKED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags =
            AccessibilityServiceInfo.DEFAULT or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        // No debounce. With one, every event (a tab tap too) arrived at
        // least this late, and Instagram's steady content changes during
        // playback kept pushing it back. Reads are coalesced here instead.
        info.notificationTimeout = 0
        info.packageNames = INSTAGRAM_PACKAGES.toTypedArray()
        serviceInfo = info
    }

    private fun describeCurrentServiceInfo(): String {
        val info = serviceInfo ?: return "cfg=missing"
        val packages = info.packageNames?.joinToString(",") ?: "(all)"
        val canRetrieveWindowContent =
            (info.capabilities and
                AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        val enabledFlags = buildList {
            if ((info.flags and AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS) != 0) {
                add("includeNotImportant")
            }
            if ((info.flags and AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) != 0) {
                add("reportViewIds")
            }
            if ((info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS) != 0) {
                add("interactiveWindows")
            }
        }.ifEmpty { listOf("none") }

        return buildString {
            append("cfg packages=")
            append(packages)
            append(" eventTypes=0x")
            append(info.eventTypes.toString(16))
            append(" flags=")
            append(enabledFlags.joinToString(","))
            append(" canRetrieveWindowContent=")
            append(canRetrieveWindowContent)
        }
    }

    // ---- Ongoing status notification ----

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val existing = notifMgr.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.accessibility_service_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        notifMgr.createNotificationChannel(channel)
    }

    /**
     * One plain line: on, paused (with a countdown) or off, and a button
     * to pause for a minute or resume. Posted only when that state
     * changes; the user can turn it off in the app.
     */
    private fun updateStatusNotification() {
        if (!settings.showNotification) {
            notifMgr.cancel(STATUS_NOTIF_ID)
            return
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_logo)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .setCategory(Notification.CATEGORY_SERVICE)
        val pausedUntil = settings.pausedUntil
        when {
            !settings.blockingEnabled -> builder
                .setContentTitle(getString(R.string.notif_title_off))
                .setContentText(getString(R.string.notif_text_off))
                .setShowWhen(false)
            pausedUntil > System.currentTimeMillis() -> builder
                .setContentTitle(getString(R.string.notif_title_paused))
                .setContentText(getString(R.string.notif_text_paused))
                .setShowWhen(true)
                .setWhen(pausedUntil)
                .setUsesChronometer(true)
                .setChronometerCountDown(true)
                .addAction(notifAction(NotificationActionReceiver.ACTION_RESUME, R.string.notif_action_resume))
            else -> builder
                .setContentTitle(getString(R.string.notif_title_active))
                .setContentText(getString(R.string.notif_text_active))
                .setShowWhen(false)
                .addAction(notifAction(NotificationActionReceiver.ACTION_PAUSE, R.string.notif_action_pause))
        }
        notifMgr.notify(STATUS_NOTIF_ID, builder.build())
    }

    private fun notifAction(action: String, label: Int): Notification.Action {
        val intent = Intent(this, NotificationActionReceiver::class.java).setAction(action)
        val pending = PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_logo),
            getString(label),
            pending,
        ).build()
    }

    companion object {
        private const val TAG = "ReelBlock/Service"
        // Long enough to suppress stale repeats on the same screen while
        // still allowing a deliberate second visit immediately after we
        // have navigated away.
        private const val BLOCK_COOLDOWN_MS = 1500L

        // Time-based fallback for scroll events that carry no pager
        // index (the index comparison in [isDmReelSwipeScroll] is the
        // primary swipe signal). Field logs timed settling scrolls at
        // 971 ms, 2046 ms and 2325 ms after the allow — they scale with
        // video load time, hence the generous window. Also gates how
        // long signatures are withheld after a DM allow.
        private const val DM_REEL_SCROLL_GRACE_MS = 3000L

        // Hard ceiling on how many BACK/tab-click actions we fire
        // against the same screen without an intervening screen
        // transition. Refires are spaced BLOCK_REFIRE_MS apart and only
        // happen while blocked-screen events are still live, so the
        // ceiling caps a genuinely-stuck session (~15 s of persistence)
        // rather than stale-event storms; the counter resets on every
        // successful escape via the BLOCKING→IDLE transition.
        private const val MAX_CONSECUTIVE_BLOCKS = 6

        /** How long after the last physical block action the event path
         *  waits before concluding the navigation didn't land and
         *  firing again. Longer than any stale-event window (1–2 s). */
        private const val BLOCK_REFIRE_MS = 2500L

        /** How recent the last blockable-screen classification must be
         *  for blind BACK presses to fire — proof the user is still on
         *  the reel surface even when Samsung's root is null/lying. */
        private const val BLOCKED_EVENT_FRESH_MS = 1500L

        // One vsync frame — enough to let the overlay's addView get
        // dispatched to WindowManager before performGlobalAction hits
        // AccessibilityManager, imperceptible to the user.
        private const val BACK_FRAME_DELAY_MS = 16L
        private const val DEFAULT_REELS_FALLBACK_TAB = "home"

        /** At most one "no queryable tree" log entry per this interval —
         *  the rest are counted and folded into the next entry. */
        private const val NULL_TREE_LOG_INTERVAL_MS = 1000L

        /** While a cover is up, how often the front window is checked. */
        private const val COVER_GUARD_POLL_MS = 250L

        /** Event-driven front checks run at most this often. */
        private const val FRONT_CHECK_MIN_GAP_MS = 100L

        /** How many Instagram window ids to remember. */
        private const val IG_WINDOW_MEMORY = 8

        /** At most one log entry per this interval for identical
         *  (screen, action, route) repeats; the rest are counted. */
        private const val REPEAT_LOG_INTERVAL_MS = 1000L

        /** Post-block watchdog cadence and retry ceiling. 700 ms is long
         *  enough for a tab click / BACK to land and the tree to settle;
         *  6 attempts ≈ 4 s of persistence before giving up. */
        private const val NAV_VERIFY_DELAY_MS = 700L
        private const val MAX_NAV_ATTEMPTS = 6

        /** Window-list poll cadence for leave-Instagram detection. Two
         *  consecutive misses are required, so a departure is noticed
         *  ~1–2 s after the user switches away. */
        private const val PRESENCE_POLL_MS = 1000L

        private const val CHANNEL_ID = "reel_block_status"
        private const val STATUS_NOTIF_ID = 0xBEEF

        private val INSTAGRAM_PACKAGES = setOf(
            "com.instagram.android",
            "com.instagram.android.debug",
            "com.instagram.lite",
        )
    }
}

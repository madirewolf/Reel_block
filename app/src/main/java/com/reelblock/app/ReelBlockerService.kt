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
import android.os.Handler
import android.os.Looper
import android.util.Log
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
 * diagnostic summary, **and mirrored into an ongoing status
 * notification** so the user can see the live state without leaving
 * Instagram.
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

    /** Running count of every accessibility event we've been handed,
     *  regardless of package. Surfaces in the notification so a growing
     *  counter proves the service is alive even before the user opens
     *  Instagram. */
    private var eventCount: Long = 0L

    /** Most recent non-Reels bottom-nav tab seen inside Instagram. */
    private var lastNonReelsBottomTab: String? = null

    /** Timestamp when a DM-launched reel became allowed. */
    private var dmAllowedSinceMs: Long = 0L

    /** Pager page index of the allowed DM reel, latched from the first
     *  scroll event that reports one. A later scroll on the SAME index
     *  is the pager settling (harmless); a different index is a real
     *  swipe. Int.MIN_VALUE = not latched yet. */
    private var dmReelBaselinePageIndex: Int = Int.MIN_VALUE

    /** Dedupe state for "Instagram event but no queryable tree" bursts —
     *  fired en masse while another window covers Instagram. */
    private var lastNullTreeRecordAt: Long = 0L
    private var suppressedNullTreeEvents: Int = 0

    /** Last time the status notification was actually posted — used to
     *  throttle the ~600 updates/sec Instagram's event storms would
     *  otherwise cause. */
    private var lastNotifPostAt: Long = 0L

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
     * copy here and posts [treeWalkRunnable]; the walk then reads the
     * CURRENT tree once for every event that arrived meanwhile.
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
    private var feedCoverCountLogged = -1
    private val feedRefreshRunnable = Runnable { refreshFeedReelCovers() }
    private var treeWalkPosted = false
    private val treeWalkRunnable = Runnable { runPendingTreeWalk() }

    /** Our own tab clicks echo back as click events; see [SelfTabClickEcho]. */
    private val selfTabClickEcho = SelfTabClickEcho()

    /** Leave-Instagram detection: polls the window list while a session
     *  is live, because no event arrives when the user goes elsewhere. */
    private val presence = InstagramPresence(INSTAGRAM_PACKAGES)
    private var presenceWatchActive = false
    private val presenceCheck = Runnable { checkInstagramPresence() }

    /** Debug builds only — see [DebugTreeDump]. */
    private var debugDumpReceiver: BroadcastReceiver? = null

    private val settingsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            SettingsStore.KEY_BLOCKING_ENABLED -> {
                if (!settings.blockingEnabled) {
                    stateMachine.reset()
                    clearBlockingCooldown()
                    overlay.hide()
                    feedCovers.clear()
                } else {
                    scheduleFeedRefresh()
                }
                updateStatusNotification(note = if (settings.blockingEnabled) "blocking on" else "blocking off", force = true)
            }
            SettingsStore.KEY_HIDE_FEED_REELS -> {
                if (settings.hideFeedReels) scheduleFeedRefresh() else feedCovers.clear()
            }
            SettingsStore.KEY_ALLOW_DM_REELS -> {
                stateMachine.allowDmReels = settings.allowDmReels
                updateStatusNotification(note = if (settings.allowDmReels) "dm reels allowed" else "dm reels blocked", force = true)
            }
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
        overlay = BlockingOverlayController(this)
        feedCovers = FeedReelCovers(this)
        settings = SettingsStore(this)
        stateMachine.allowDmReels = settings.allowDmReels
        settings.registerListener(settingsListener)
        notifMgr = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        configureRuntimeServiceInfo()
        val configDiagnostics = describeCurrentServiceInfo()
        Log.i(TAG, "Accessibility service connected: $configDiagnostics")
        stateMachine.reset()
        clearBlockingCooldown()
        lastNonReelsBottomTab = null
        registerDebugDumpReceiver()
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
        // Promote to foreground-visible via an ongoing notification — not
        // a true FGS (AccessibilityService already has a privileged
        // binding) but the ongoing flag gives OEM task-killers reason to
        // leave us alone, and crucially gives the user a live readout of
        // what the service sees without bouncing out of Instagram.
        updateStatusNotification(note = "ready", diagnostics = configDiagnostics, force = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val handleStart = System.currentTimeMillis()
        val pkg = event.packageName?.toString() ?: return
        val eventClassName = event.className?.toString()

        // Counter ticks for every single event — regardless of package —
        // so the notification proves the service is alive even when
        // Instagram isn't open. Without this, a dead service and an
        // idle-on-the-home-screen service look identical.
        eventCount += 1

        if (!isInstagramPackage(pkg)) {
            // Defensive only: the package filter means Android does not
            // deliver other apps' events, so leaving Instagram is caught
            // by the window-list poll in [checkInstagramPresence].
            resetSessionState()
            updateStatusNotification(
                screen = ScreenType.UNKNOWN,
                action = SessionStateMachine.Action.NONE,
                note = "idle (pkg=$pkg)",
            )
            return
        }

        startPresenceWatch()
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SELECTED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            // Only the foreground app receives taps and scrolls.
            presence.onInstagramEvidence()
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
            ReelDetector.bottomNavTabForEvent(event.contentDescription, tapSourceId)
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

        if (!settings.blockingEnabled) {
            // Still emit a log entry so the user can confirm events are
            // arriving even while paused; without this a "paused" app
            // looks identical to a "dead" app in the UI.
            recordEvent(
                event = event,
                screen = fastScreen ?: ScreenType.UNKNOWN,
                action = SessionStateMachine.Action.NONE,
                signature = null,
                diagnostics = "paused (blocking disabled) cls=$eventClassName",
                treeSource = "none",
                selectedBottomTab = null,
                handleLatencyMs = System.currentTimeMillis() - handleStart,
                fastPath = fastScreen != null,
            )
            return
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
            setOnHomeFeed(navEventTab == ReelDetector.TAB_HOME)
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
        if (!treeWalkPosted) {
            treeWalkPosted = true
            handler.post(treeWalkRunnable)
        }
    }

    private fun runPendingTreeWalk() {
        treeWalkPosted = false
        val event = pendingWalkEvent ?: return
        pendingWalkEvent = null
        if (!settings.blockingEnabled) return
        val handleStart = System.currentTimeMillis()
        val eventClassName = event.className?.toString()

        val sourceNode: AccessibilityNodeInfo? = try {
            event.source
        } catch (_: Throwable) {
            null
        }
        val rootNode: AccessibilityNodeInfo? = try {
            rootInActiveWindow
        } catch (t: Throwable) {
            Log.w(TAG, "rootInActiveWindow threw", t)
            null
        }
        val selection = selectInstagramNode(rootNode, sourceNode)
        val snapshot = selection.node?.toSnapshot()

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
        val screen = ReelDetector.detectScreen(snapshot)
        val selectedBottomTab = ReelDetector.extractSelectedBottomTab(snapshot)
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
        setOnHomeFeed(screen == ScreenType.OTHER && selectedBottomTab == ReelDetector.TAB_HOME)
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

        val action = if (dmReelSwipe) {
            stateMachine.onReelScroll()
        } else {
            stateMachine.handleScreen(screen, signatureForStateMachine(screen, signature, priorMode))
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
    ) {
        var resolvedScreen = screen
        var signature: String? = null
        var selectedBottomTab: String? = null
        var treeSource = "none"
        var rootPackageName: String? = null
        var sourcePackageName: String? = null
        var treeDebug: ReelDetector.TreeDebug? = null
        var activeNode: AccessibilityNodeInfo? = null

        // Only bother snapshotting when we actually need a signature —
        // i.e. a reel viewer while we might be in DM-allowed mode, or
        // when we need to know which bottom tab is selected.
        val needsTree = screen == ScreenType.REEL_VIEWER || screen == ScreenType.REELS_TAB
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
            val selection = selectInstagramNode(rootNode, sourceNode)
            treeSource = selection.sourceLabel
            rootPackageName = selection.rootPackageName
            sourcePackageName = selection.sourcePackageName
            activeNode = selection.node
            selection.node?.toSnapshot()?.let { snap ->
                treeDebug = ReelDetector.inspectTree(snap)
                signature = ReelDetector.extractReelSignature(snap)
                selectedBottomTab = ReelDetector.extractSelectedBottomTab(snap)
                if (!selectedBottomTab.isNullOrBlank() && selectedBottomTab != "reels") {
                    lastNonReelsBottomTab = selectedBottomTab
                }
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
        if (resolvedScreen != ScreenType.OTHER) setOnHomeFeed(false)

        val priorMode = stateMachine.currentMode()
        val previousScreenBefore = stateMachine.currentPrevScreen()
        if (resolvedScreen == ScreenType.REELS_TAB || resolvedScreen == ScreenType.REEL_VIEWER) {
            lastBlockedScreenEventAt = System.currentTimeMillis()
        }
        val dmReelSwipe = isDmReelSwipeScroll(event, resolvedScreen, priorMode)

        val action = if (dmReelSwipe) {
            stateMachine.onReelScroll()
        } else {
            stateMachine.handleScreen(resolvedScreen, signatureForStateMachine(resolvedScreen, signature, priorMode))
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
        dmReelBaselinePageIndex = Int.MIN_VALUE
        lastBlockedScreenEventAt = 0L
        stateMachine.reset()
        clearBlockingCooldown()
        overlay.hide()
        setOnHomeFeed(false)
    }

    private fun setOnHomeFeed(value: Boolean) {
        onHomeFeed = value
        if (value) scheduleFeedRefresh() else feedCovers.clear()
    }

    private fun scheduleFeedRefresh() {
        if (feedRefreshPosted) return
        feedRefreshPosted = true
        handler.post(feedRefreshRunnable)
    }

    /**
     * Covers every reel on screen in the Home feed. One id lookup
     * (`row_feed_photo_imageview`) instead of a tree walk, so the boxes
     * keep up with scrolling.
     */
    private fun refreshFeedReelCovers() {
        feedRefreshPosted = false
        if (!onHomeFeed || !settings.blockingEnabled || !settings.hideFeedReels) {
            feedCovers.clear()
            return
        }
        val root = currentInstagramRoot() ?: return
        val pkg = root.packageName?.toString() ?: return
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
        feedCovers.show(rects)
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
                updateStatusNotification(note = "idle (left Instagram)", force = true)
            }
        }
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
            " idx=${event.fromIndex}/${event.toIndex} base=$dmReelBaselinePageIndex"
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
        updateStatusNotification(
            screen = screen,
            action = action,
            signature = signature,
            note = eventTypeName,
            diagnostics = diagnostics,
        )
    }

    private fun triggerBlock(
        screen: ScreenType,
        activeNode: AccessibilityNodeInfo?,
    ): BlockAttempt {
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

        overlay.showBlocked()

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
                        blockedScreenEventFresh()
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
                blockedScreenEventFresh()
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
        if (!settings.blockingEnabled) return
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
            screen = ReelDetector.detectScreen(root.toSnapshot())
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
        // Still on the blocked screen: keep the reel covered while we retry.
        overlay.showBlocked()
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
     * Primary signal: the pager PAGE INDEX carried by the scroll event.
     * The first indexed scroll after the allow latches the baseline (the
     * pager settling on the shared reel); any later scroll on the same
     * index is more settling; a DIFFERENT index is a real swipe — even
     * inside the time grace, which closes the old fast-swipe hole.
     *
     * Fallback (event carries no index): time-based grace. Field logs
     * timed the settling scroll at 971 ms, 2046 ms and 2325 ms after the
     * allow — it scales with video load time, so the fixed window kicks
     * slow-loading legit reels; that is exactly why the index signal is
     * primary and the window is generous.
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
        val idx = when {
            event.toIndex >= 0 -> event.toIndex
            event.fromIndex >= 0 -> event.fromIndex
            else -> Int.MIN_VALUE
        }
        if (idx != Int.MIN_VALUE) {
            if (dmReelBaselinePageIndex == Int.MIN_VALUE) {
                dmReelBaselinePageIndex = idx
                return false
            }
            if (idx == dmReelBaselinePageIndex) return false
            dmReelBaselinePageIndex = idx
            return true
        }
        return (System.currentTimeMillis() - dmAllowedSinceMs) >= DM_REEL_SCROLL_GRACE_MS
    }

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
    ): String? {
        if (screen != ScreenType.REEL_VIEWER || signature == null) return signature
        val dmEntryImminent = priorMode == SessionStateMachine.Mode.IDLE &&
            stateMachine.currentPrevScreen() == ScreenType.DM_THREAD
        val inDmGrace = priorMode == SessionStateMachine.Mode.DM_ALLOWED_REEL &&
            dmAllowedSinceMs > 0L &&
            System.currentTimeMillis() - dmAllowedSinceMs < DM_REEL_SCROLL_GRACE_MS
        return if (dmEntryImminent || inDmGrace) null else signature
    }

    /**
     * Compose a one-line audit trail for every event we handle: why we
     * did or didn't act, what the cooldown and mode looked like, and
     * which route we took if we acted. This is the primary field a
     * human (or Claude) reads when diagnosing "it's blocking stuff it
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

    private fun selectInstagramNode(
        rootNode: AccessibilityNodeInfo?,
        sourceNode: AccessibilityNodeInfo?,
    ): NodeSelection {
        val rootPackageName = rootNode?.packageName?.toString()
        val sourcePackageName = sourceNode?.packageName?.toString()
        return when {
            rootNode != null && isInstagramPackage(rootPackageName.orEmpty()) ->
                NodeSelection(rootNode, "root", rootPackageName, sourcePackageName)
            sourceNode != null && isInstagramPackage(sourcePackageName.orEmpty()) ->
                NodeSelection(sourceNode, "source", rootPackageName, sourcePackageName)
            else ->
                NodeSelection(null, "none", rootPackageName, sourcePackageName)
        }
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
            dmReelBaselinePageIndex = Int.MIN_VALUE
            return
        }
        if (stateMachine.currentMode() != SessionStateMachine.Mode.DM_ALLOWED_REEL) {
            dmAllowedSinceMs = 0L
            dmReelBaselinePageIndex = Int.MIN_VALUE
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
        info.notificationTimeout = 80
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

    private fun updateStatusNotification(
        screen: ScreenType = ScreenType.UNKNOWN,
        action: SessionStateMachine.Action = SessionStateMachine.Action.NONE,
        signature: String? = null,
        note: String? = null,
        diagnostics: String? = null,
        force: Boolean = false,
    ) {
        // Event storms (content-changed floods, scrolls) would otherwise
        // post hundreds of notification updates per second. Blocks and
        // explicit state changes always post; routine ticks are capped.
        val now = System.currentTimeMillis()
        if (!force &&
            action != SessionStateMachine.Action.BLOCK_REEL &&
            now - lastNotifPostAt < NOTIF_MIN_INTERVAL_MS
        ) {
            return
        }
        lastNotifPostAt = now

        val openLog = PendingIntent.getActivity(
            this, 0,
            Intent(this, LogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (settings.blockingEnabled)
            getString(R.string.notif_title_active)
        else
            getString(R.string.notif_title_paused)

        val summary = buildString {
            append("events=")
            append(eventCount)
            append(" · screen=")
            append(screen.name)
            append(" · mode=")
            append(stateMachine.currentMode().name)
            append(" · act=")
            append(action.name)
            if (!signature.isNullOrBlank()) {
                append(" · sig=")
                append(signature.take(24))
            }
            if (!note.isNullOrBlank()) {
                append(" · ")
                append(note)
            }
        }

        val bigText = buildString {
            append(summary)
            if (!diagnostics.isNullOrBlank()) {
                append('\n')
                append(diagnostics)
            }
        }

        // The notification itself is tappable (setContentIntent -> log).
        // No separate action button — keeps the layout compact and
        // avoids OEM quirks around null action icons.
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(bigText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openLog)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setCategory(Notification.CATEGORY_SERVICE)

        notifMgr.notify(STATUS_NOTIF_ID, builder.build())
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

        /** Minimum spacing between routine status-notification posts.
         *  Blocks and settings changes bypass this. */
        private const val NOTIF_MIN_INTERVAL_MS = 500L

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

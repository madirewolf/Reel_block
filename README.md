# Reel It In

(The repository is still called Reel Block.)

An Android app that blocks Instagram Reels, **except** the one reel a friend sent you in a DM. Swipe to the next one and the blocker kicks in immediately.

Not an all-or-nothing app blocker. The infinite feed is the problem; a link someone actually sent you is not.

Runs entirely on-device. No network calls, no analytics, nothing leaves the phone. MIT licensed.

---

## The rules

- Main Reels feed (the bottom-nav "Reels" tab): always blocked.
- A reel opened from inside a DM thread: you get to watch that specific reel.
- The moment you swipe to the next one: blocked.
- Leave Instagram and come back: state resets.
- The DM exemption is a switch on the home screen. Turn it off and every reel is blocked, DM-shared or not.
- Reels in the Home feed are covered as you scroll. That is its own switch, on by default.
- Holding a reel tile on Explore (the long-press preview): blocked, even with your finger still down. Photo previews stay.

The app screen is a three-step setup (turn on the accessibility service, allow notifications, keep it running) and three options:

| Option | What it does |
|---|---|
| **Block reels** | Master switch. The Reels tab, and any reel you open or swipe to. Off shows "Paused" and greys out the other two. |
| **Allow reels from DMs** | The reel a friend sent you plays. Swiping to the next one is blocked. |
| **Hide reels in Home feed** | Opaque boxes over every reel in the feed, tracked as it scrolls. |

---

## What makes it interesting technically

Instagram is a closed app. There is no API for "what screen is on top". The Android Accessibility framework is the only supported way to read the active window's content on a non-rooted device, which makes this an exercise in inferring application state from a view tree you do not control and that changes without warning.

### Pure core, thin driver

The interesting logic is deliberately kept away from anything Android:

| File | Role | Android-aware? |
|---|---|---|
| `NodeSnapshot.kt` | Immutable POJO copy of an `AccessibilityNodeInfo` subtree | only at the copy boundary |
| `ReelDetector.kt` | Maps a snapshot to a `ScreenType` and extracts a reel signature | no |
| `SessionStateMachine.kt` | Decides `ALLOW_REEL` / `BLOCK_REEL` / `NONE` from the screen sequence | no |
| `ReelBlockerService.kt` | The AccessibilityService. Event plumbing, overlay, navigation, notification | yes, entirely |

`AccessibilityNodeInfo` is close to untestable: it needs the live accessibility framework and it recycles under you. Copying the four fields that matter (`viewIdResourceName`, `contentDescription`, `text`, `className`, plus `isSelected`, `isScrollable`, and children) into a plain data class means the detector and the state machine can be unit-tested on the JVM against hand-built trees with no mocking, no emulator, and no Instagram. Three test suites do exactly that, driven by a `NodeSnapshotFactory` of realistic fixtures, and they cover every ALLOW/BLOCK/NONE transition plus an integration pass that runs the detector and the state machine together.

### The swipe detection problem

The hard question is not "is this a reel". It is "**is this still the same reel**".

Two independent signals answer it, because either one alone has a failure mode:

1. **The pager's page index is the primary path** (`DmReelSwipe.kt`). On Instagram 457 a reel opened from a DM sits in a `ViewPager`. Its scroll events carry the page index: 0 while the shared reel settles, 1 once you swipe to the next one. The service remembers the page the shared reel is on and blocks only when a scroll reports a *different* page of that pager. A scroll with no page index is never a swipe. That matters: opening the comments sheet on a friend's reel scrolls a plain `View` with no index, and the old rule ("any scroll while in DM mode is a swipe") closed the friend's reel the moment you read its comments.
2. **Signature comparison is the backstop,** for a swipe that somehow carries no index. On entry the service latches the reel's signature. If a later signature differs, the user moved to a different reel.

The block cover goes up on the swipe's scroll event itself, before the tree is read (about 55 ms later on a Galaxy S24). If the read then shows you are still on the allowed reel, the cover comes down again (`CoverPolicy.dropEarlyCover`).

The subtlety is which signatures are allowed to trigger that comparison. A signature is either a stable `user:` prefix (the author's username) or a coarse `txt:` caption hash fallback. The caption hash drifts as text loads frame by frame, so comparing two `txt:` signatures produces false BLOCKs on the reel you are legitimately allowed to watch. So the comparison only fires when **both** the latched and the incoming signature are stable `user:` ones. A coarse latch gets upgraded in place the moment a stable signature appears, and a null latch gets filled by the first signature seen, because signatures often load late.

### Which tree to read

The obvious way to snapshot the screen is `rootInActiveWindow`. It is frequently null during window transitions ([issuetracker #223809542](https://issuetracker.google.com/issues/223809542)), which is precisely when a Reels screen is being pushed, and on Samsung it can even report the launcher while Instagram is in front.

The service reads `rootInActiveWindow` when it really is Instagram's window (a full, fresh tree) and falls back to the event's own `event.source` subtree when it is not. Which source produced a given snapshot is recorded in the detection log, so a misclassification can be traced back to whether the tree was even available.

One screen needs a second window. Holding a tile on Explore opens a preview (`peek_container`) in Instagram's main window and a Like/Share menu in a separate popup window, and the popup has focus. When the active window is that menu, the service finds the preview in the main window through `getWindows()` and classifies it. A preview holding a video surface or player ids is a reel, whatever tab is selected under it; BACK closes it even while the finger is down.

### Blocking is a sequence, and it verifies itself

On BLOCK the service puts up a full-screen cover so the reel is not visible, then navigates away: on the Reels tab it clicks the last non-Reels bottom tab you were on (Home by default), and on a reel opened anywhere else it fires `GLOBAL_ACTION_BACK`. Then it **verifies**: every 700 ms it re-reads the screen and, if you are still on a reel, tries again, up to six times. Firing once and hoping is not enough; Instagram sometimes ignores a click made mid-transition. The click itself echoes back as an accessibility "Home clicked" event, which says nothing about whether Instagram actually switched tabs, so the service ignores the echo of its own click and trusts only the tree.

The cover is an accessibility overlay (`TYPE_ACCESSIBILITY_OVERLAY`), which an accessibility service may draw without the "Display over other apps" permission. It does not take touches, it stays up while the service is still retrying, it comes down 250 ms after you land somewhere allowed, and it can never stay more than 1.2 s past the last attempt, so a failed navigation can't strand Instagram behind it. A cooldown and a consecutive-block circuit breaker keep the service from fighting you in a loop.

### Fast enough not to see the reel

A Reels screen starts playing within a frame or two, so the cover has to beat it:

- **A tap on the Reels tab blocks at once.** The click (and the select event Instagram 457 sends ~200 ms before it) is enough when it is under 300 ms old. Waiting for a tree that shows Reels selected only let Reels play.
- **Three threads.** Tree reads run on their own threads (`ReadThreads.kt`), the cover is added on a display-priority thread (`OverlayThread.kt`), and the main thread only owns state. A slow read of the Home feed can no longer hold up the cover.
- **No event batching.** `notificationTimeout="0"` in the service config, so Android delivers each event immediately instead of batching them for up to 80 ms.
- **A pre-inflated cover.** The cover view is inflated when the service starts and re-added on each block. The first block after a restart took ~120 ms to inflate; now it is ~45 ms to add. A warm cover is on screen ~40 ms after the tap.
- **Reads that started before a block are dropped** (`CoverPolicy.isTreeReadStale`). A pre-tap tree still showing Home used to undo the block.

### Noticing that you left

The service only receives Instagram's events, so Android never tells it that you switched to another app. While a session is live it checks the on-screen window list once a second; two checks in a row without an Instagram window, with another app in front, count as leaving, and the session state resets. The notification shade and the lock screen don't count.

### Survivability

Instagram rotates its resource IDs across releases, which will eventually break any detector built on them. Two things blunt that:

- **`endsWith` matching**, so only the suffix after `com.instagram.android:id/` needs to be maintained, and the ID lists are grouped constants rather than scattered string literals.
- **A live detection log** in the app: a ring buffer of recent events with the classification, the signature, the decision, and a diagnostic summary of the tree. When blocking feels wrong on a new Instagram version, you reproduce the failure, open the log, and see exactly which screen classified as what. That turns a re-tune from a debugging session into reading a list.

Detection also does not depend on a single mechanism. Classification is a weighted combination of ID markers, reel action content-descriptions, video surface presence, story viewer markers, DM markers, bottom tab selection state, tree depth, and class names, plus a separate path that classifies directly off bottom-nav selection events. Losing one signal degrades accuracy instead of breaking the app.

### Privacy by construction, not by promise

```xml
<!-- app/src/main/res/xml/accessibility_service_config.xml -->
android:packageNames="com.instagram.android,com.instagram.android.debug,com.instagram.lite"
```

The service is scoped to three package names. It is not that the app chooses not to look at your banking app; the OS never delivers it those events. Verify it yourself in that one file, it is short.

---

## Decision flow

```
                 ┌─────────────────────┐
                 │ Any Instagram event │
                 └──────────┬──────────┘
                            ▼
                 ┌─────────────────────┐
                 │   classify screen   │
                 └──────────┬──────────┘
                            ▼
 REELS_TAB ───────────────► BLOCK
 OTHER / UNKNOWN ─────────► NONE    (preserve last-known screen)
 DM_THREAD / DM_INBOX ────► NONE    (reset mode to IDLE)
 REEL_VIEWER
   ├─ "Allow reels from DMs" off ─► BLOCK  (no exemption at all)
   ├─ prev = DM_THREAD  ─► ALLOW   (latch reel signature)
   ├─ mode = DM_ALLOWED
   │   ├─ pager moved to another page ─► BLOCK  (swiped to next reel)
   │   ├─ scroll with no page index ──► ALLOW  (comments, caption)
   │   ├─ same author sig ─► ALLOW
   │   └─ diff author sig ─► BLOCK  (backstop)
   └─ else              ─► BLOCK
```

---

## Build and install

**Android Studio:** open the folder, let it fetch Gradle, the Android SDK 34, and Kotlin, then Sync and Run with a device connected over USB debugging.

**Command line** (needs JDK 17 and the Android SDK with `ANDROID_HOME` set):

```bash
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows
./gradlew installDebug         # to a connected device
./gradlew testDebugUnitTest    # unit tests
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

---

## First-run setup on the phone

The app screen walks you through it as Setup 1, 2, 3. Each step has a button that jumps to the right settings page and turns into a check mark when done.

1. **Turn on Reel It In.** Toggle it on under Settings, Accessibility, Installed apps (on Android 13+ you may first need to allow restricted settings from the app's info page if you sideloaded the APK).
2. **Allow notifications** (optional), for the status notification.
3. **Keep it running** (optional), the battery-optimization exemption, so the phone doesn't close it.

Android will warn you that an accessibility service can "observe your actions" and "retrieve window content". Reel Block needs exactly that level of access to see which Instagram screen is on top. Because the service is scoped to Instagram's package names only, it cannot observe anything else.

Once step 1 is done the status reads **On** and you can open Instagram. **Detection log** at the bottom of the screen shows screens classifying in real time.

---

## Requested capabilities

Everything the manifest declares:

| Permission / capability | What for |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` (on the `<service>`) | Receiving Instagram's accessibility events. |
| `POST_NOTIFICATIONS` | The ongoing status notification that mirrors the live decision state so you can see what the service is doing without leaving Instagram. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | So aggressive OEM battery managers do not kill the service mid-session. |
| `<queries>` for the three Instagram packages | Package visibility on Android 11+, so the service actually receives Instagram's events. |

No internet permission, no overlay permission, no device admin, no root. Nothing extra is needed to come back after a reboot: Android rebinds an enabled accessibility service by itself.

---

## Tuning detection

Instagram's resource IDs rotate across releases. If blocking feels off on a new version:

1. Open the app, tap **Detection log**.
2. Reproduce the failure in Instagram (for example, open the Reels tab).
3. Come back to the log. The mis-classified screen shows up as `OTHER` instead of `REELS_TAB`, or the reverse.
4. Add the new id to the right list in `ReelDetector.kt`: `TAB_BAR_ID_SEGMENTS`, `TAB_ID_SEGMENTS`, `REEL_VIEWER_ID_SEGMENTS`, `DM_THREAD_ID_SUBSTRINGS`, or `DM_INBOX_ID_SUBSTRINGS`.
5. Rebuild and reinstall.

Ids are matched on the part after `com.instagram.android:id/`.

Instagram 457 (late 2026) renamed the bottom bar to `tab_bar`, made Messages a tab, keeps every tab's page mounted (so hidden pages are skipped), and calls stories `reel_*` and Reels `clips_*`. Its long-press preview is `peek_container`, with its menu in `context_menu`.

---

## Layout

```
app/src/main/java/com/reelblock/app/
  ReelBlockerService.kt         the AccessibilityService (the large, impure one)
  ReelDetector.kt               pure classifier + signature extraction
  SessionStateMachine.kt        pure ALLOW/BLOCK/NONE policy
  NodeSnapshot.kt               testable copy of an AccessibilityNodeInfo subtree
  ScreenType.kt                 the enum
  BlockingOverlayController.kt  full-screen cover (accessibility overlay)
  FeedReelCovers.kt             boxes over reels in the Home feed
  CoverTiming.kt                pure: when the cover may come down
  CoverPolicy.kt                pure: fresh Reels-tab taps, stale reads, early covers
  DmReelSwipe.kt                pure: is a scroll a swipe off the DM reel?
  OverlayThread.kt              thread that adds and removes covers
  ReadThreads.kt                threads that read Instagram's tree
  InstagramPresence.kt          pure: did the user leave Instagram?
  SelfTabClickEcho.kt           pure: ignore the echo of our own tab click
  DebugTreeDump.kt              debug builds: dump the live tree to JSON
  DetectionLog.kt               in-memory ring buffer of recent decisions
  MainActivity.kt               Setup 1-2-3 and the three options
  LogActivity.kt                live detection log + clipboard export
  SettingsStore.kt              SharedPreferences flags
  ServiceStatus.kt              permission helpers
app/src/test/java/com/reelblock/app/
  NodeSnapshotFactory.kt              hand-built view-tree fixtures
  ReelDetectorTest.kt                 classification
  SessionStateMachineTest.kt          every transition and edge case
  DetectorStateMachineIntegrationTest.kt
  Instagram457Test.kt                 the 457 tab bar, hidden pages, Explore preview
  FeedReelRectsTest.kt                which feed boxes are reels
  DmReelSwipeTest.kt, CoverPolicyTest.kt
  CoverTimingTest.kt, InstagramPresenceTest.kt, SelfTabClickEchoTest.kt
```

---

## Honest state

It works, on the Instagram versions it has been tuned against. Specifically:

- **The detection ruleset is empirical and dated.** It is reverse-engineered from a view tree Instagram can change in any release, with no contract and no notice. Expect to re-tune. The live log exists because of this, not in spite of it.
- **The service is not small.** `ReelBlockerService.kt` is around 1,950 lines. The pure core it drives is small (`SessionStateMachine.kt` at ~210 lines, `ReelDetector.kt` at ~680), but the Android-facing driver has accumulated the event plumbing, navigation verification, cooldown handling, notification state, and runtime service reconfiguration that a real accessibility service ends up needing. It is the part most in need of a refactor.
- **Test coverage stops at the boundary.** The pure classifier and state machine are well covered. The service itself has no tests, because testing it would mean testing the accessibility framework.
- **A DM swipe shows the next reel while your finger is still dragging.** Drag scrolls report page 0, the same as the shared reel settling, so there is nothing to tell them apart until the page lands. The cover is up ~60 ms after it does.
- **Instagram only.** The architecture would extend to TikTok or YouTube Shorts, but nothing in the ruleset does today.

---

## License

MIT. Do whatever you like with this. If you extend the detection ruleset for a new Instagram release, a PR would be lovely.

---

## Screenshots

TODO. Save to `docs/img/` and put the first two side by side at the top.

1. **`blocked.gif`** — the core demo, and worth the effort to get right. Screen-record on a real device: open Instagram, tap the Reels tab, and capture the cover dropping and the app routing you back out. About 6 seconds. Use `adb shell screenrecord --time-limit 10 /sdcard/demo.mp4` then `adb pull`, and trim it. Nothing else explains the app this fast.
2. **`dm-exemption.gif`** — the feature that makes this different from every other blocker. Open a DM-shared reel (it plays), then swipe up (it blocks immediately). About 8 seconds. If you make only one recording, make it this one.
3. **`detection-log.png`** — the live log with a real scroll of classified events showing screen types, signatures, and decisions. This is the screenshot that tells another engineer the thing is debuggable.
4. **`home.png`** — the app screen with all three setup steps checked and the three options on.

Blur or crop any DM sender names, avatars, and reel content before publishing. Use a throwaway account for the recordings if you have one.

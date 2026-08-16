# Reel Block

An Android app that blocks Instagram Reels — **except** when a reel is
opened from a DM, and only for the single reel that was shared. Swipe
to the next one and the blocker kicks in immediately.

- Main Reels feed (the bottom-nav "Reels" tab) → always blocked.
- A reel shared inside a DM thread → you can watch that specific reel.
- The moment you swipe to the next reel, it gets blocked.
- Leaving Instagram and coming back is fine; state resets.
- The DM exemption is an **option** — the "Allow reels from DMs"
  switch on the home screen. Turn it off and every reel is blocked,
  DM-shared or not.

The app runs entirely on-device. No network calls, no analytics, no
data leaves your phone.

---

## How it works

Reel Block is an **Android Accessibility Service**. When Instagram is
in the foreground it receives the same window/content/scroll events
Instagram fires for screen readers, snapshots the on-screen view tree,
and classifies what you're looking at.

The detection logic lives in three small pure-Kotlin files, each under
200 lines:

| File | Role |
|---|---|
| [`ReelDetector.kt`](app/src/main/java/com/reelblock/app/ReelDetector.kt) | Maps a view-tree snapshot to a `ScreenType` (REELS_TAB, REEL_VIEWER, DM_THREAD, DM_INBOX, OTHER). |
| [`SessionStateMachine.kt`](app/src/main/java/com/reelblock/app/SessionStateMachine.kt) | Decides `ALLOW_REEL` / `BLOCK_REEL` / `NONE` from the sequence of screens. |
| [`ReelBlockerService.kt`](app/src/main/java/com/reelblock/app/ReelBlockerService.kt) | The Accessibility Service — the only Android-aware piece. Thin driver on top of the other two. |

When `BLOCK_REEL` fires the service:

1. Drops a full-screen [`BlockingOverlayController`](app/src/main/java/com/reelblock/app/BlockingOverlayController.kt) on top of Instagram so you can't see the reel.
2. Fires `GLOBAL_ACTION_BACK` twice with a short delay to pop you out of the reel viewer.
3. Auto-dismisses the overlay after ~1.2 s.

There is also an in-memory [`DetectionLog`](app/src/main/java/com/reelblock/app/DetectionLog.kt) exposing the last 200 events to
the UI so you can watch the classifier work in real time from the
"View live detection log" button on the home screen.

### The allow/block rules in one diagram

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
   │   ├─ same author sig ─► ALLOW
   │   └─ diff author sig ─► BLOCK  (user swiped to next reel)
   └─ else              ─► BLOCK
```

A `TYPE_VIEW_SCROLLED` event while in DM_ALLOWED mode is a short-circuit
to BLOCK — it's the clearest signal that the user swiped. The signature
comparison is the backstop for swipes the scroll signal misses (e.g. a
swipe inside the post-open grace window): it only fires when both the
latched and the incoming signature are stable `user:` (author username)
signatures, never on the coarse `txt:` caption-hash fallback, which
drifts as text loads frame-by-frame.

### Why Accessibility Service?

Instagram is a closed app. There is no public API to ask "what screen
is on top?". The Accessibility framework is the only supported way to
read the active window's content on a non-rooted device. Reel Block's
service config narrows what it listens to down to the three Instagram
package names only:

```xml
<!-- app/src/main/res/xml/accessibility_service_config.xml -->
android:packageNames="com.instagram.android,com.instagram.android.debug,com.instagram.lite"
```

No other app is ever inspected.

---

## Build & install

### Option A — Android Studio (easiest)

1. Install **Android Studio Hedgehog (2023.1)** or newer.
2. `File → Open…` and pick this folder.
3. Studio will fetch the Gradle wrapper, Android SDK 34, and Kotlin
   1.9.22 automatically. Click **Sync**, then **Run ▶** with your
   phone connected via USB (USB debugging enabled).

### Option B — Command line

Prerequisite: **JDK 17** and the **Android SDK (command-line tools)**
installed, with `ANDROID_HOME` set. Accept the platform licenses with
`yes | sdkmanager --licenses`.

```bash
# From the project root. On the very first run, Gradle will download
# the wrapper jar automatically.
gradle wrapper --gradle-version 8.7 --distribution-type bin   # one-time

# Debug APK
./gradlew assembleDebug        # macOS / Linux
gradlew.bat assembleDebug      # Windows PowerShell / CMD

# Install it on a connected device (USB debugging on):
./gradlew installDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Running the unit tests

```bash
./gradlew testDebugUnitTest
```

Tests cover both the detector (screen classification from hand-built
view-tree fixtures) and the state machine (every BLOCK/ALLOW/NONE
transition and edge case).

---

## First-run setup on the phone

Reel Block needs two OS-level grants. The home screen walks you through
both; each has a button that jumps you straight to the right settings
page.

1. **Display over other apps** (`SYSTEM_ALERT_WINDOW`) — so the
   blocking cover can appear on top of Instagram.
2. **Accessibility Service** — toggle *Reel Block monitor* to ON under
   **Settings → Accessibility → Installed services**.

Android will warn you that an accessibility service can "observe your
actions" and "retrieve window content". Reel Block needs exactly that
level of access to see which Instagram screen is on top. Because the
service is scoped to Instagram's package names only, it literally
cannot observe anything else — verify this yourself in
[`accessibility_service_config.xml`](app/src/main/res/xml/accessibility_service_config.xml).

Once both grants are green, flip the "Blocking enabled" switch on and
open Instagram. The home screen also has a **View live detection log**
button — tap it, open Instagram in another window, and watch screens
classify in real time.

---

## Tuning detection

Instagram's resource IDs rotate across releases. If blocking ever feels
off on a new version:

1. Open the app, tap **View live detection log**.
2. Reproduce the failure in Instagram (e.g. open Reels tab).
3. Come back to the log — the mis-classified screen will show up as
   `OTHER` instead of `REELS_TAB`, or vice versa.
4. Add a new ID marker to the appropriate list in
   [`ReelDetector.kt`](app/src/main/java/com/reelblock/app/ReelDetector.kt):
   - `REEL_VIEWER_ID_MARKERS`
   - `BOTTOM_TAB_ID_MARKERS`
   - `DM_THREAD_ID_MARKERS`
   - `DM_INBOX_ID_MARKERS`
5. Rebuild and reinstall.

The detector uses `endsWith` matching so only the suffix after
`com.instagram.android:id/` needs to be added.

---

## Project layout

```
Reel_block/
├── build.gradle.kts                Root build file
├── settings.gradle.kts
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── app/
│   ├── build.gradle.kts            App module
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/reelblock/app/
│       │   │   ├── MainActivity.kt              Dashboard
│       │   │   ├── LogActivity.kt               Live detection log
│       │   │   ├── ReelBlockerService.kt        The AccessibilityService
│       │   │   ├── BlockingOverlayController.kt Full-screen cover
│       │   │   ├── ReelDetector.kt              Pure classifier
│       │   │   ├── SessionStateMachine.kt       Pure state machine
│       │   │   ├── NodeSnapshot.kt              AccessibilityNodeInfo copy
│       │   │   ├── DetectionLog.kt              In-memory ring buffer
│       │   │   ├── SettingsStore.kt             Single SharedPreferences flag
│       │   │   ├── ServiceStatus.kt             Permission helpers
│       │   │   └── ScreenType.kt                Enum
│       │   └── res/
│       │       ├── layout/                      activity_main, activity_log, blocking_overlay, item_log
│       │       ├── values/, values-night/       colors, strings, themes
│       │       ├── drawable/                    icon + chip/card backgrounds
│       │       ├── mipmap-anydpi-v26/           adaptive launcher icon
│       │       └── xml/
│       │           ├── accessibility_service_config.xml
│       │           └── backup_rules.xml
│       └── test/java/com/reelblock/app/
│           ├── NodeSnapshotFactory.kt                   Test fixtures
│           ├── ReelDetectorTest.kt
│           ├── SessionStateMachineTest.kt
│           └── DetectorStateMachineIntegrationTest.kt
└── README.md
```

---

## Requested capabilities

The manifest declares exactly these:

| Permission / capability | What for |
|---|---|
| `BIND_ACCESSIBILITY_SERVICE` (via `<service>`) | Receiving Instagram's accessibility events. |
| `SYSTEM_ALERT_WINDOW` | Drawing the full-screen "Reel blocked" cover. |
| `POST_NOTIFICATIONS` | Reserved for future status notifications — currently unused. |
| `RECEIVE_BOOT_COMPLETED` | Reserved for optional auto-start behaviour. |
| `QUERY_ALL_PACKAGES` (with explicit `<queries>`) | So the Accessibility service actually sees events from `com.instagram.android` on Android 11+. |

That's the full list. No device-admin, no root, no internet.

---

## License

MIT — do whatever you like with this. If you extend the detection
ruleset for a new Instagram release, a PR would be lovely.

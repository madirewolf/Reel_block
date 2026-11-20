# Google Play release

What's ready, what you do, and in what order.

## Already done in the code

- Targets Android 16 (API 36). Google requires this for new apps.
- Shows a disclosure before it opens Accessibility settings, and goes on only on **Agree**. Play requires this for apps that use the accessibility API but aren't accessibility tools.
- Signs release builds when `keystore.properties` exists. That file is git-ignored.
- Privacy policy is live at https://reelitin.limiliminal.com/privacy.

## 1. Make your upload key (once, on this PC)

Choose your own password. Keep the `.jks` file and the password somewhere safe. Play App Signing holds the real app key, and this upload key can be reset through Play support if you lose it.

```powershell
& "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore "$HOME\reelitin-upload.jks" -alias upload -keyalg RSA -keysize 4096 -validity 10000
```

Then create `keystore.properties` in the repo root:

```
storeFile=C:/Users/<you>/reelitin-upload.jks
storePassword=<your password>
keyAlias=upload
keyPassword=<your password>
```

## 2. Build the bundle

```powershell
.\gradlew bundleRelease
```

The bundle is `app\build\outputs\bundle\release\app-release.aab`. Raise `versionCode` in `app/build.gradle.kts` for every upload.

## 3. Play Console: create the app

- Name: **Reel It In**. Type: App. Price: Free.
- The package name `com.reelblock.app` is permanent once uploaded.

## 4. App content (Policy → App content)

| Form | Answer |
|---|---|
| Privacy policy | https://reelitin.limiliminal.com/privacy |
| Ads | No ads |
| App access | All functionality is available without special access. In the notes, tell the reviewer it needs Instagram installed and signed in, then Setup step 1. |
| Content rating | Fill in the questionnaire. Utility app, no user content. |
| Target audience | 13 and up. Don't tick under 13, which pulls in the Families policy. |
| Data safety | No data collected, no data shared. The app has no internet permission. |
| Accessibility API | Declare it. Not an accessibility tool. Purpose: detects Instagram Reels on screen and covers them so the user can avoid them. Needs a video (unlisted YouTube link): open the app, tap Setup 1, show the disclosure, tap Agree, turn the service on, open a reel in Instagram, and show it blocked. |
| Government, financial, health, news | No |

**Battery permission:** the app declares `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for Setup step 3. Play restricts that permission to a short list of use cases, and a reel blocker isn't on it, so review may flag it. If it does, drop the permission and have step 3 open the battery settings list instead. It's a small change.

## 5. Store listing

- **Short description** (80 characters max): `Block Instagram Reels. Keep your DMs, posts and stories.`
- **Full description:**

  > Reel It In blocks Instagram Reels on Android, so you can use Instagram without falling into the endless feed.
  >
  > You choose where reels are blocked: the Reels tab, Home, Search & Explore, your own profile, and DMs. A reel a friend sends you still plays. Swipe on to the next one and it's blocked.
  >
  > Calm by design: pick a plain color, a quiet pattern or your own photo for the block screen. Need a minute? Tap Pause 1 min in the notification and blocking comes back by itself. Covers vanish the moment you leave Instagram.
  >
  > Private: Reel It In has no internet permission. It uses Android's accessibility service to read Instagram's screen, and only Instagram's, to tell when a reel is showing. Nothing leaves your phone.
  >
  > Not affiliated with Instagram or Meta.

- Graphics: the icon and feature graphic are ready in `docs/play/` (`icon-512.png`, `feature-1024x500.png`). You still need at least 2 phone screenshots: the app screen, and the block screen inside Instagram. Use an account with nothing private on screen.
- Category: Productivity. Contact email: required by Play and shown publicly, so use one you're happy to publish.
- Don't put Instagram's logo or name in the icon or app title (trademark policy). Saying it works with Instagram in the description is fine.

## 6. Testing, then production

Personal developer accounts made after 13 Nov 2023 must run a **closed test with at least 12 testers, opted in for 14 days in a row**, before they can apply for production.

1. Testing → Closed testing → create a track and upload the `.aab`.
2. Add the 12+ testers' Google accounts (an email list or a Google Group) and send them the opt-in link.
3. Keep the track running for 14 days. Pausing it resets the clock.
4. Dashboard → Apply for production. Answer the questions about the test, then roll out.

# F-Droid: build-server check and store listing

**Time:** 2026-11-05, ~15:00–15:35 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "You can start with steps 3 and 4 while I test the application builds". App ID confirmed: `dev.recorddisc.player`.

## Step 3: Can F-Droid's build server build it?
- **Setup:** a fresh clone of the public `Record_Player` repo with JDK 21, no `.env`, no `local.properties` (SDK from `ANDROID_HOME`) and no signing variables. `./gradlew :androidApp:assembleRelease` gives `androidApp-release-unsigned.apk`. Unsigned is right: F-Droid signs with its own key.
  - The APK is `dev.recorddisc.player`, versionName 0.1.0, versionCode 1, compileSdk 37, targetSdk 36.
- **Memory:** in this Codespace (8 GB) the first two attempts were killed for lack of memory, because the project lets Gradle use 4 GB plus 3 GB for Kotlin. The check passed with command-line limits (Gradle 2.5 GB, Kotlin in-process, 2 workers). Project settings are unchanged; F-Droid's servers have more memory.
- **Permissions:** only foreground service (playback, data sync), notifications and wake lock. **No internet permission.**
- **Notes for submission:**
  - compileSdk 37 must be available to F-Droid's SDK tools; drop to 36 if their reviewers ask.
  - The empty `iosApp` module prints "No Kotlin Targets Declared" on every build (harmless). Removing it is proposed to the project manager.

## Step 4: Store listing (`fastlane/metadata/android/en-US/`)
- `title.txt`: "Record Player".
- `short_description.txt`: 79 characters (limit 80).
- `full_description.txt`: features, privacy and licence, about 1,600 characters. Every claim matches the code, and "no internet permission" was checked in the built APK.
- `changelogs/1.txt`: notes for versionCode 1.
- `images/icon.png`: the existing 512×512 app icon (same design as the Android launcher icon).
- `images/phoneScreenshots/` (6, 1080×2340): Now Playing with lyrics, Home, Library, a playlist, Convert to Audio, Settings.
  - They're rendered from the app's screen-test setup with the made-up sample library, so no real music or personal data appears.
  - **Generator:** `DesktopScreenshotTest.storeScreenshots`, which runs only when asked:
    `STORE_SCREENSHOTS=1 ./gradlew --no-daemon :shared:jvmTest --tests '*storeScreenshots'`, then copy `shared/build/screenshots/store/*.png`.

## Impacts
New listing files and a test only; no app code changed. Not committed.

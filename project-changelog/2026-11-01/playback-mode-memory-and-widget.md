# Playback mode memory, media controls, and widget mockup

- **Time:** 2026-11-01 10:19 UTC
- **Details:** Updated the existing Android HTML mockup to present Shuffle and Loop controls in the app player, Android system media notification, and a new home-screen widget preview. The control states are shown as remembered and synchronized across the mockup surfaces. The widget includes artwork, track title/artist, Previous, Shuffle, Play/Pause, Next, and Loop controls. Shuffle and Loop mockup actions update all three surfaces.
- **Impact:** Mockup-only proposal; no application source changed. Implementation is pending user review and approval of the mockup.
- **Approved By:** User (requested persistent Shuffle/Loop modes across Android and desktop, media-banner controls, a widget with artwork/title and full playback controls, and selected the existing HTML mockup)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Implementation

- **Time:** 2026-11-01 10:57 UTC
- **Details:** Implemented saved Shuffle and Repeat preferences for Android and desktop, restored them at startup, and kept the lyrics editor's temporary Repeat One behavior from overwriting the saved preference. Added Android media-notification Shuffle/Repeat actions and a home-screen playback widget with artwork, track details, Previous, Shuffle, Play/Pause, Next, and Repeat controls. Added the Android widget provider and action receiver; widget actions wait for app-model initialization to avoid startup preference races, and newly added widgets receive an immediate state refresh. Renamed app-facing branding to “Record Player” and added Android/desktop icon assets while preserving the internal application ID and “Record Disc”/`.rdplaylist` bundle naming.
- **Impact:** Shuffle and Repeat settings persist across launches; their Android media controls and widget reflect and update current playback state. Added initialization and persistence regression coverage. Prior source/build versions are stored under `version-control/shared/2026-11-01_1036/`, `version-control/androidApp/2026-11-01_1036/`, and `version-control/desktopApp/2026-11-01_1036/`.
- **Validation:** `./gradlew --no-daemon --max-workers=1 :shared:jvmTest --tests 'dev.recorddisc.player.app.AppModelTest' :androidApp:compileDebugKotlin :desktopApp:compileKotlin :androidApp:assembleDebug` passed. `git diff --check` passed. APK SHA-256: `2836ccf02a62a90a3ab5fe8f296b63f133f53f2d3cf4f01d28786d701de27259`. Device runtime behavior was not verified because no Android device/emulator is available. The previously verified desktop app-image build includes the icon; `.deb` packaging remains blocked by missing `fakeroot`.
- **Approved By:** User (requested remembered Shuffle/Repeat modes, Android media-banner controls and a widget; approved “Record Player” branding, the selected icon concept, and Android/desktop coverage)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Playback loading diagnostic

- **Time:** 2026-11-01 11:15 UTC
- **Details:** After the user reported that tapping any visible library song opened Now Playing without a title or error toast, temporarily restored the previous trusted-controller MediaSession connection callback and removed the custom notification Shuffle/Repeat command buttons and layout updates. The saved Shuffle/Repeat preferences, in-app controls, widget, URI handling, and shuffle ordering remain enabled. The user tested the diagnostic APK and confirmed songs now load and play. This confirms the regression is in the removed notification MediaSession customization, but does not isolate which part of that customization caused it.
- **Impact:** At the user's request, the stable diagnostic version remains in place. Its APK is at `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (SHA-256 `5d4dfb18c2b8d5735cda7bacbb9753de7bdf03f8389f34946168a53f7cb947ec`). Notification Shuffle/Repeat buttons are temporarily absent; saved modes and widget controls remain available. The pre-diagnostic source and APK are backed up under `version-control/androidApp/2026-11-01_1108/`. `:androidApp:assembleDebug` and `git diff --check` passed.
- **Approved By:** User (approved a reversible diagnostic build, confirmed song playback works with it, and chose to keep this stable version)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Widget tap opens app

- **Time:** 2026-11-01 11:17 UTC
- **Details:** Bound the playback widget's root surface to an immutable Activity PendingIntent targeting `MainActivity`, so tapping the widget body opens the app. Existing Previous, Shuffle, Play/Pause, Next, and Repeat button broadcasts are unchanged.
- **Impact:** Rebuilt the diagnostic APK; its SHA-256 is `0dab0e307463375bcb7bc6d6f2d7cff005b23de785af76d380cdf475187be26c`. Previous widget source/layout and APK are backed up under `version-control/androidApp/2026-11-01_1116/`. `:androidApp:assembleDebug` and `git diff --check` passed. Device tap behavior has not been runtime-tested after this change.
- **Approved By:** User (approved the widget tap fix while preserving playback button actions)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

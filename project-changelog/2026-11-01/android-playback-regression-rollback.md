# Roll back lyrics-editor pause-at-track-end playback feature

- **Time:** 2026-11-01 07:43 UTC
- **Details:** The user confirmed that the saved Android debug APK from 02:23 plays audio, while the newer APK does not. Compared playback sources with the pre-feature backups: the subsequent playback-engine feature added a `MediaSession` custom command and modified `onConnect` to pause queue progression while editing lyrics. Removed that feature from the shared player API, Android service/player, lyrics editor, desktop player, and related tests. Preserved the current source and APK under `version-control/{shared,androidApp}/2026-11-01_0743/`.
- **Impact:** Restores the prior default Media3 controller connection path as a focused regression rollback. The lyrics editor no longer pauses at track end on Android or desktop. Existing error-toast diagnostics are retained. The new APK still needs playback verification on the user's device; this environment has no `adb`.
- **Approved By:** User (requested immediate restoration of playback after confirming the prior APK works)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Verification

- **Time:** 2026-11-01 07:46–07:47 UTC
- **Details:** `:androidApp:compileDebugAndroidTestKotlin` and `:androidApp:assembleDebug` passed. Focused `DesktopPlayerTest.playsTheQueueThroughAndStopsAtTheEnd` and `DesktopPlayerTest.pauseSeekNextAndPrevious` passed. A first broader `DesktopPlayerTest` run had one timeout in the previously observed `stopsWhenEverySongFails` case; the focused playback tests passed on rerun.
- **Impact:** Rebuilt the debug APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk` (SHA-256 `8db0c1a86e2269bea509197567648305c6ef92ee1b21040de1fb183ba66ddf80`). Android device playback is not verified because `adb` is unavailable.
- **Approved By:** User (requested the playback regression be fixed)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

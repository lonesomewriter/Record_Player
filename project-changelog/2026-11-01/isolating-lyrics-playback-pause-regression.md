# Add Android tests for lyrics-editor pause-mode regression

- **Time:** 2026-11-01 08:02–08:15 UTC
- **Details:** Backed up the pre-test Android playback sources and test file under `version-control/androidApp/2026-11-01_0802/`, and the shared player interface, desktop implementation, and test fake under `version-control/shared/2026-11-01_0804/`. Temporarily restored the lyrics-editor pause-at-track-end player method and Android custom session command, then added Android instrumentation cases covering initial playback with the mode enabled, resetting it before loading a new queue, and ordinary Play/Pause commands after the custom session connection. Test setup explicitly resets pause-at-end to false.
- **Impact:** Diagnostic source changes only. No APK was assembled. Playback remains verified only for the previously built rollback APK; do not install a build from this experimental source state before the instrumentation cases run.
- **Verification:** `:androidApp:compileDebugAndroidTestKotlin` passed. No Android device is attached (`adb` unavailable), so the instrumentation tests have not executed. An initial compile attempt failed because the shared player interface had not yet been restored; after restoring the required interface and implementations, compilation passed.
- **Approved By:** User (approved temporarily restoring the feature in source only to add and compile isolated instrumentation tests; no experimental APK)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Follow-up source comparison

- **Time:** 2026-11-01 08:19 UTC
- **Details:** Compared the experimental Android player and playback service against the saved pre-experiment rollback snapshots, and inspected the lyrics-editor lifecycle and command dispatch.
- **Impact:** The pause-at-track-end flag only configures `ExoPlayer.setPauseAtEndOfMediaItems`; the lyrics editor enables it on composition and disables it on disposal. The custom `onConnect` adds a session command while carrying forward the default player commands, and the existing local-media resolution path is unchanged. This source comparison did not establish a root cause for playback failing to start.
- **Verification:** No device is attached, so runtime A/B instrumentation remains unavailable. No APK was built and no application source was changed.
- **Approved By:** User (requested further tests or code comparisons)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Confirmed-working APK backup

- **Time:** 2026-11-01 08:24 UTC
- **Details:** Copied the current debug APK to `version-control/androidApp/2026-11-01_0824/rollback-success01.apk` before proceeding. The source and backup SHA-256 are both `8db0c1a86e2269bea509197567648305c6ef92ee1b21040de1fb183ba66ddf80`.
- **Impact:** Preserves the user-confirmed working rollback APK separately from subsequent diagnostic builds.
- **Approved By:** User (explicitly requested this named backup before trying the safe approach)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Attempted device instrumentation

- **Time:** 2026-11-01 08:25 UTC
- **Details:** Ran `gradle :androidApp:connectedDebugAndroidTest --no-daemon`. Gradle assembled the app/test APKs but could not execute tests because the Android test runner reported `No connected devices!`. Restored the default debug APK output from `rollback-success01.apk`; both APKs now have the preserved known-good SHA-256.
- **Impact:** No runtime test result. The backup remains available, and the default APK output is the user-confirmed working rollback. No application source was changed in this attempt.
- **Approved By:** User (approved trying the safe playback-regression approach after backing up the current build)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Switch lyrics editing to immediate pause

- **Time:** 2026-11-01 08:42 UTC
- **Details:** Backed up the pre-change playback and test sources under `version-control/androidApp/2026-11-01_0835/` and `version-control/shared/2026-11-01_0835/`. In shared `rememberDraft`, pause through the normal player API when the lyrics editor opens for a track; do not resume when it closes. Removed the experimental pause-at-track-end API, desktop behavior, and Android MediaSession custom command so ordinary playback uses the known rollback connection path. Added a focused Compose regression test for entering and closing the editor.
- **Impact:** Opening either platform's lyrics editor now pauses playback immediately. The selected behavior intentionally does not auto-resume on close. The risky custom MediaSession command is no longer in source. The default debug APK remains the confirmed-working rollback build; no APK was rebuilt from the changed source.
- **Verification:** `:shared:jvmTest --tests dev.recorddisc.player.ui.DesktopScreenshotTest.lyricsEditorPausesPlaybackAndDoesNotResumeWhenClosed` passed. `:androidApp:compileDebugAndroidTestKotlin` passed. Android instrumentation runtime remains unverified because no device/emulator is connected. An initial attempt to run the large screenshot `home` test was terminated by SIGTERM; the focused editor test then passed.
- **Approved By:** User (selected immediate pause on editor open with no auto-resume)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Assemble immediate-pause APK

- **Time:** 2026-11-01 08:43 UTC
- **Details:** Built `androidApp/build/outputs/apk/debug/androidApp-debug.apk` with `gradle :androidApp:assembleDebug --no-daemon`. Build succeeded; the APK SHA-256 is `60e7630ea4afb7e6f7a6b70c2658dc1f5c322fc8a11223ee6d3720127daae790`.
- **Impact:** The default debug APK now contains immediate pause on lyrics-editor entry and no automatic resume on close. `rollback-success01.apk` remains unchanged with SHA-256 `8db0c1a86e2269bea509197567648305c6ef92ee1b21040de1fb183ba66ddf80`.
- **Verification:** APK assembly succeeded. It has not been installed or runtime-tested on Android because no device/emulator is connected.
- **Approved By:** User (approved the safe approach after requesting the rollback APK backup)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Fix lyrics-editor playback lock and export completion notification

- **Time:** 2026-11-01 09:00 UTC
- **Details:** Backed up the pre-fix APK and affected source/test files under `version-control/androidApp/2026-11-01_0855/` and `version-control/shared/2026-11-01_0855/`. In the lyrics editor, kept the entry pause and disabled starting playback from its transport control; the control still pauses if playback is active. For export notifications, remove the foreground progress notification at terminal status, cancel any previous completion card at a new export, and post completion/failure using a separate notification ID. Added a Compose regression assertion that the editor's playback control is disabled.
- **Impact:** The editor no longer offers a Play action that can restart the queue while lyrics are being edited. Completed exports no longer leave the 100% foreground progress notification behind; a separate dismissible completion/failure result is used.
- **Verification:** Focused test `:shared:jvmTest --tests dev.recorddisc.player.ui.DesktopScreenshotTest.lyricsEditorPausesPlaybackAndDoesNotResumeWhenClosed` passed. `:androidApp:compileDebugKotlin` and `:androidApp:compileDebugAndroidTestKotlin` passed. `:androidApp:assembleDebug` succeeded. Device-level playback and notification shade behavior are not runtime-verified because no Android device/emulator is connected. The built APK SHA-256 is `87c4fba2091019340c2257032b24c083233f7fb8dc32556cef1b184a8012116e`; pre-fix APK backup SHA-256 is `60e7630ea4afb7e6f7a6b70c2658dc1f5c322fc8a11223ee6d3720127daae790`.
- **Approved By:** User (selected pausing with Play disabled in the editor and a separate completion notification)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Allow lyrics playback without queue advancement

- **Time:** 2026-11-01 09:18 UTC
- **Details:** Backed up the pre-change APK and playback/editor sources under `version-control/androidApp/2026-11-01_0910/` and `version-control/shared/2026-11-01_0910/`. Restored the lyrics editor's Play/Pause behavior and scoped pause-at-track-end to the editor's `DisposableEffect`, resetting it on disposal. The Android playback service exposes the custom command only to this app's trusted controller and delegates unrelated commands to Media3's default handler. Desktop playback also pauses at the current song's end and disables gapless preloading while the mode is enabled. Added Android instrumentation coverage for startup, pause-at-end and restoring normal queue advancement, plus desktop playback/editor tests.
- **Impact:** Songs remain audible while writing lyrics, but do not auto-advance at the current song's end; closing the editor restores normal queue behavior.
- **Verification:** Focused shared editor test `:shared:jvmTest --tests dev.recorddisc.player.ui.DesktopScreenshotTest.lyricsEditorPlaybackDoesNotAdvanceAndRestoresQueueBehaviorWhenClosed` passed. Focused desktop end-of-track test `:shared:jvmTest --tests dev.recorddisc.player.playback.DesktopPlayerTest.pauseAtTrackEndLetsTheSongPlayWithoutAdvancing` passed. Android app and instrumentation sources compiled. `:androidApp:assembleDebug` succeeded. Android runtime tests could not run because `adb` and a connected device/emulator are unavailable. An initial parallel compile exhausted the Kotlin daemon and exposed a test-fake setter name clash; the name was corrected and the serial combined validation passed.
- **APK:** `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, SHA-256 `bbea1e0473d48b07fb1b1ea6c15d38de56da586e102eea33cf16d363d7f5a656`. Pre-change APK backup: `version-control/androidApp/2026-11-01_0910/androidApp-debug-before-lyrics-playback.apk`, SHA-256 `87c4fba2091019340c2257032b24c083233f7fb8dc32556cef1b184a8012116e`.
- **Approved By:** User (requested playback be enabled while lyrics editing prevents queue advancement)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Replace pause-at-end with scoped repeat-one

- **Time:** 2026-11-01 09:41 UTC
- **Details:** Backed up the affected shared and Android playback sources/tests plus the current debug APK under `version-control/shared/2026-11-01_0941/` and `version-control/androidApp/2026-11-01_0941/`. Removed the pause-at-track-end player API and Android MediaSession custom command. The lyrics editor now switches to Repeat One on entry and restores the repeat mode captured on entry when disposed; the effect is scoped to the player, so changing the edited track does not restore the mode while the editor remains open. Updated the Compose test to verify repeat-mode restoration and that opening the editor while playback is active does not pause it.
- **Impact:** Playback should continue audibly and replay the current song while lyrics editing is open. Closing the editor restores the prior repeat mode. Opening the editor does not issue a pause command.
- **Verification:** The first test run exposed an outdated assertion that expected playback to be stopped after editor close; updated the test to assert playback stays active. The focused Compose restoration/playback test and desktop repeat-one end-of-track test then passed. Android app and instrumentation Kotlin compilation passed, and `:androidApp:assembleDebug` succeeded. The rebuilt APK SHA-256 is `8515404a1f2500426134dc8ec26e4f6a44dae431c6ce7ce4d10f9e8cbe1da797`; the prior APK backup SHA-256 is `bbea1e0473d48b07fb1b1ea6c15d38de56da586e102eea33cf16d363d7f5a656`. Device-level Android behavior is not verified because no device/emulator is available.
- **Approved By:** User (requested replacing the pause behavior with looping during lyrics editing, restoring the previous mode on close, and keeping playback unpaused)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

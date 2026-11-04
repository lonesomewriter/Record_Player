# Surface Android playback errors

- **Time:** 2026-11-01 07:15–07:18 UTC
- **Details:** Backed up the pre-change `PhoneApp.kt`, `AndroidPlayer.kt`, and debug APK. Updated Android playback errors to expose only a Media3 error code or a generic service-connection message, then routed the error through the existing bottom toast so playback failures are visible without exposing file paths or URIs.
- **Impact:** Adds diagnostic feedback when Android playback fails; queue selection and playback controls are unchanged. The new debug APK is `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.
- **Verification:** `git diff --check`, `:shared:jvmTest --tests dev.recorddisc.player.ui.PhoneNavigationTest`, and `:androidApp:assembleDebug` passed. Device playback could not be exercised in this environment.
- **Approved By:** User (approved the sanitized diagnostic toast and debug build)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Ensure player errors reach the toast

- **Time:** 2026-11-01 07:21–07:22 UTC
- **Details:** Backed up `PhoneApp.kt` and the preceding debug APK. Updated the existing toast stream to merge regular app messages with non-null playback errors from the player's state, instead of forwarding playback errors as transient shared-flow events.
- **Impact:** Playback error diagnostics remain visible through the existing toast even if an error is already present when the UI subscribes. No additional UI element was added.
- **Verification:** `git diff --check`, `:shared:jvmTest --tests dev.recorddisc.player.ui.PhoneNavigationTest`, and `:androidApp:assembleDebug` passed. Device playback still needs on-device verification.
- **Approved By:** User (approved surfacing playback errors in a toast and rebuilding the debug APK)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

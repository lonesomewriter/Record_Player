# Android background playlist export and navigation

- **Time:** 2026-11-01 03:31 UTC
- **Details:** Begin implementing the approved Android `.rdplaylist` background-export flow, including progress that remains visible in-app until hidden, an Inbox entry point, cancellation, and a foreground notification. Request notification permission on the first export without blocking export when denied. Track the Now Playing return destination for Library and playlist playback. Leave playlist-to-audio conversion out of scope.
- **Impact:** Android playlist bundle exports and phone navigation. Desktop export behavior and M3U exports remain unchanged. Back up the current source versions before editing.
- **Approved By:** User (approved the implementation and confirmed the Android custom-format progress popup should remain)
- **Performed By:** Copilot

## Verification

- **Time:** 2026-11-01 03:50 UTC
- **Details:** Focused phone-navigation and AppModel background-export/cancellation tests passed. `:androidApp:assembleDebug` succeeded using in-process Kotlin compilation, and `:desktopApp:compileKotlin` succeeded. `git diff --check` passed. A broader AppModel test run and an isolated rerun both timed out in the existing `watchedFolderRescansAfterChangesSettle` case while waiting for the added song; the folder-watching path was not changed. Earlier combined Gradle invocations lost their daemon, so Android validation was rerun serially.
- **Impact:** Android and desktop compile checks pass, and the new state/navigation regression tests pass. The watched-folder test remains an unrelated failing check; device-level notification/SAF behavior was not runtime-tested.
- **Approved By:** User (approved implementation)
- **Performed By:** Copilot

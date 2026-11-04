# Add "View file location" to song overflow menus

- **Time:** 2026-11-01 20:45:00 UTC
- **Change:** Added a "View file location" option to the song "…" overflow menu on both Android and Desktop. Tapping it opens the song's containing folder in the system file manager. Mockups were updated first and approved.

## Details

**Mockups (gitignored, local only):**
- `project-notes/mockups/disc-concept-android.html`: Added `i-folder` icon to SVG sprite; added "View file location" button to the `moreSheet` overflow menu (after "Add to playlist").
- `project-notes/mockups/disc-concept-desktop.html`: Added "View file location" button to the `songOptionsModal` overflow menu (after "Add to playlist") plus JS click handler that closes the modal and shows a toast.

**Platform abstraction (`Platform.kt`, `Platform.android.kt`, `Platform.jvm.kt`):**
- Added `@Composable expect fun viewFileLocation(location: String, folder: String?)`.
- Android: opens the folder/document URI via `Intent.ACTION_VIEW` with URI permissions.
- Desktop (JVM): opens the parent directory via `java.awt.Desktop.getDesktop().open(dir)`.

**UI wiring:**
- `PhoneApp.kt` (Now Playing overflow menu): added "View file location" as the last menu item; uses a `viewFileTrack` state variable to trigger the composable `viewFileLocation` call after the menu closes; added `TrackEntity` import.
- `Library.kt` (`SongMenuButton`): added "View file location" as the last menu item; same state-variable trigger pattern.
- `DesktopApp.kt`: removed `ProvideViewFileLocation` provider (not needed — `viewFileLocation` is called directly from the composable scope).

**Backups:**
- Prior source versions stored under `version-control/shared/2026-11-01_2015/`.
- Debug APK backed up as `version-control/androidApp/2026-11-01_2015/stable-debug02/` (SHA-256 `0dab0e307463375bcb7bc6d6f2d7cff005b23de785af76d380cdf475187be26c`).

## Verification
- `:shared:compileKotlinMetadata` — passed
- `:androidApp:compileDebugKotlin` — passed (warnings are pre-existing `!!` assertions)
- `:desktopApp:compileKotlin` — passed
- `:androidApp:assembleDebug` — passed; new APK at `androidApp/build/outputs/apk/debug/androidApp-debug.apk`
- `:shared:jvmTest` — 126 completed, pre-existing executor exit 143 (unrelated to this change; known `stopsWhenEverySongFails` timeout)

## Impacts
- Users can now reveal a song's containing folder/file from the overflow menu on both platforms.
- No new dependencies, environment variables, secrets or PII.
- Android runtime behavior not device-tested (no `adb`/emulator available).
- `project-notes/` is gitignored, so mockup changes are local only.

## Performed By
Cline (AI agent)

## Approved By
Project Manager (User): requested the "View file location" overflow menu option.
# Port the 2026-11-04 mockup changes into the app

**Time:** 2026-11-04, 15:09–15:55 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager. Plan approved 2026-11-04 (`project-notes/mockup-port-and-build-plan.md`, part A, Q1–Q9).

## Details

### Navigation and playback
- **Directional page slides** (Android, `PhoneApp.kt`):
  - Every screen change carries a `NavDirection`, and pages slide opposite to the finger; the old page slides out too.
  - The system Back button slides like a left swipe. Taps keep the old fade-in.
  - With reduced motion, changes only fade.
- **Tapping a song only plays it** (Android `Library.kt`): this covers rows and the album / artist Play and Shuffle buttons. Playlist Play / Shuffle already only played. Desktop was already like this.
- **Now Playing returns to where the song was last chosen** (Android):
  - A new `PlaySource` (Library / Album / Artist / Playlist) is recorded on every play. `LibraryNav` (now saveable) and the open playlist moved up into `PhoneScreens`.
  - Swipe left, ✕ and Back reopen that exact page. A deleted playlist falls back to the Playlists list.
  - The footer reads "Swipe left to return to <place>".
  - Side effect: the Library no longer forgets its open album / artist after a trip to Now Playing or an editor.

### Song menu, Song details and reordering
- **One shared ⋮ song menu** (`SongMenu` in `Library.kt`), used by library rows, album / artist rows, Now Playing, Song details and playlist rows (phone and desktop).
  - Order: View Details, Edit lyrics, Edit metadata, Add to playlist, View file location.
  - Playlist rows add a divider and Remove from playlist.
  - Move up / Move down were removed.
- **Song details** (new `SongDetails.kt`):
  - A `RecordSleeve` cover with the record peeking out (on desktop it slides further out on hover), Year / Genre / Track / Length, Play, Add to playlist, ⋮, and a "Not playing…" note.
  - Phone: a full screen. Desktop: a page over the current view, with ← back to it.
  - View Details on the playing song opens Now Playing (desktop: Home).
- **Hold-and-drag playlist reordering** (`Playlists.kt`, phone and desktop):
  - A long press lifts the row (with a haptic tick on Android); it follows the pointer, the others slide aside, and the list auto-scrolls.
  - A local copy reorders at once on drop.
  - New `AppModel.movePlaylistTrackTo` moves a song to any position, keeps missing-song rows in place, and renumbers positions without gaps.
  - Desktop shows "Hold a song, then drag it to reorder."

### Colours and settings
- **Accent fade** (`Theme.kt`): `DiscTheme` animates the accent over 360 ms, or snaps with reduced motion. The phone's Dynamic color keeps the previous cover colour until the next one is ready, so there's no flash of the preset colour.
- **Pause when muted (on) / Resume when unmuted (off)**:
  - New settings (`AppSettings`) and Settings rows on phone and desktop. Resume is greyed out while Pause is off.
  - The shared rule lives in `playback/MuteAutoPause.kt`.
  - Android: `MediaMute.kt` (system-settings observer plus volume/mute broadcasts), run inside `PlaybackService`.
  - Linux: `SystemMuteMonitor` (`pactl` / `pactl subscribe`), plus the app's volume slider at 0, wired in `desktopApp/main.kt`.
- **Use system accent** (desktop, off by default; replaces "Dynamic color · Soon"):
  - `SystemAccentMonitor` reads the xdg-desktop-portal `accent-color` via `busctl`, with GNOME's `accent-color` via `gsettings` as a fallback, and watches both for changes.
  - The switch is greyed out when neither answers. Picking a swatch turns it off.
  - The Accent color label reads "System · <name>".

### Docs
- `project-notes/documents/{feature,programming}_documentation.md` updated.
- `project-notes/mockup-port-status.md`: all items `done`, with a list of checks that need real hardware.

## Version control
- Prior versions: `version-control/shared/2026-11-04_1509/` (Library, PhoneApp, Playlists, DesktopApp, Theme, Settings, Platform, AppSettings, AppModel, PhoneNavigationTest, AppModelTest, TestFakes, DesktopScreenshotTest), `version-control/androidApp/2026-11-04_1509/PlaybackService.kt`, `version-control/desktopApp/2026-11-04_1509/main.kt`.
- New files (no prior version): `SongDetails.kt`, `MuteAutoPause.kt`, `SystemMuteMonitor.kt`, `SystemAccentMonitor.kt`, `MediaMute.kt`, `MuteAutoPauseTest.kt`, `SystemDesktopReadersTest.kt`.

## Verification
- `:shared:compileKotlinJvm`, `:desktopApp:compileKotlin` and `:androidApp:compileDebugKotlin` all pass.
- **New tests, all passing:**
  - phone navigation (swipes, Now Playing / Song details returns, saving play sources and Library pages)
  - `dragReorderMovesASongToAnyPositionAndRenumbers` (including a missing-song row)
  - 5 mute-rule cases
  - parsing of recorded `pactl` / portal / `gsettings` output
  - a screenshot test that drives the real desktop and phone UIs, checking the menu contents (no Move up), Song details, ← back, the Settings rows and the "System · Blue" label, and phone Song details → Play → Now Playing's "return to library" footer
- **Existing tests:** still pass, including the playlist ordering tests (`movePlaylistTrack` is kept).
- **Pre-existing problems, confirmed on a clean checkout of `1bc9682` with none of today's changes:**
  - `playbackModesAreRestoredAndPersisted` and `resumeOnStartReopensTheQueueWhereItWasLeftPaused` fail intermittently (timing). `playlistAddActionsOpenLibrary` failed once and then passed.
  - The full `:shared:jvmTest` run ends with "Gradle Test Executor … exit value 143" while running the heavy 90-frame desktop screenshot tests (`firstRun`, `home`, `albumsAndArtists`), so classes after them alphabetically are not run in that pass. They were run separately and pass.
- **Not testable here** (no phone, no desktop session): the swipe and drag feel, real mute detection, and the real system accent. These are listed in `project-notes/mockup-port-status.md`.

## Impacts
- These are visible behaviour changes on both platforms, as the project manager approved in the mockups.
- **No database schema change:** reordering reuses the `position` column.
- No new dependencies, permissions, environment variables, secrets or network access. The desktop runs `pactl`, `busctl` and `gsettings` locally with fixed arguments and short time limits.

## Commit and push
- **Time:** 2026-11-04, ~16:20 UTC. **Approved By:** Project manager
- Commits on `ui`: `9775e94` (build command + packaging), `b3d55fb` (mockup port), `ca6bdd8` (mockup records and backups).
- Fast-forwarded `origin/main` and `origin/ui` from `1bc9682` to `ca6bdd8` (no force push, no merge commit).
- Checked first: no secrets in the diff, and the package binaries (`*.apk`, `*.deb`) stayed gitignored; only the build logs were committed.

# Record Disc UI, Step 1: Foundation, First Run, Disc Screens (Part 3)

- **Time:** 2026-10-29 21:24:22 UTC
- **Change:** Replaced the placeholder screen with the real Record Disc UI on both platforms.
  - **Theme:** colours and accent come from the mockups.
  - **App model:** connects settings, library, scanning and player.
  - **First run:** built to the approved mockup.
  - **Android:** disc home with the arc menu, Now Playing, and the mini-player.
  - **Desktop:** sidebar, Home, the "Up next" queue, the dock, and the narrow layout.
  - **Not built yet:** Library and Settings are placeholders for the next steps. Playlists, Lyrics and Edit metadata are shown as "SOON", as the PM decided.

## Details

**Data:**
- **Settings table.** Database version 2 adds a `settings` table (`SettingEntity` / `SettingsDao`), stored as name/value text.
  - Room upgrades version-1 databases automatically (`AutoMigration(1, 2)`).
  - The schema is exported to `shared/schemas/.../2.json`.
- **`AppSettings`** gives the settings types: music folder, setup done, accent (6 presets or custom), dynamic colour, resume on start, and pause on unplug. Malformed values fall back to the defaults.
- **`LibraryScanner.scan`** now reports progress (files checked and the current file name) for the scanning screen.
- **`TagReader.readArtwork`** returns the embedded cover image. Images over 20 MB are ignored.
- **The Android scanner** falls back to the date field when the year field is empty, which is common for FLAC and Ogg files.

**App model (`AppModel`, shared):**
- **First-run scan** with a live count. The folder is saved only if music was found. "Start listening" marks setup done and queues the library, ready but not playing.
- **Later launches** queue the library straight away, then rescan quietly in the background.
- **Play button** starts the library if nothing is queued.
- **Short messages** are shown as a toast.
- **`ArtworkCache`** keeps the 80 most recently used covers in memory.

**Player:** added `setVolume` (desktop dock).
- On Linux it uses GStreamer's software volume (`g_object_set` with a double value).
- On Android it uses Media3's player volume.

**UI (shared Compose code, `ui/`):**
- **Disc.** Drawn like the mockup's CSS: grooves, sheen, a label with the cover art (or an accent gradient with "SIDE A"), and the spindle hole.
  - Same momentum as the mockups (eases up in about 1 s, coasts down in about 2 s, stops at its angle).
  - The Android marquee arc and the desktop dotted ring turn as in the mockups.
  - All motion stops when Android's animations are turned off.
- **Icons** are built from the mockups' own SVG paths.
- **Theme.** Line height scales with font size, so spacing matches the mockups.
- **Android layout (`PhoneApp`):**
  - first run;
  - home with the disc and the Library, Playlists and Settings arc buttons;
  - Now Playing: seek, shuffle, previous, play, next, repeat (off/one/all), with Lyrics, the ⋮ menu and Edit metadata marked SOON;
  - the mini-player;
  - swipe left or press Back to return to the disc.
  - System bars are dark with light icons.
- **Desktop layout (`DesktopApp`):**
  - first run;
  - sidebar;
  - Home: the disc toggles play/pause; title, artist, album · year; chips for genre, format and "Local file";
  - the "Up next" queue with track details;
  - the dock: transport, seek and volume.
  - Below 1220 dp the queue hides. Below 900 dp the sidebar becomes a top bar.

**Platforms:**
- **Android:**
  - `RecordDiscApplication` holds the model for the life of the process.
  - `MainActivity` opens Android's own folder picker and keeps **read-only** access to the chosen folder. Write access will be asked for with tag editing in part 4.
  - `AndroidLibraryPlatform` decodes cover art with Android's own decoder, downsampled.
- **Desktop:**
  - `main.kt` builds the model with `DesktopPlayer`.
  - "Change…" opens Java's built-in folder dialog in the system look.
  - `DesktopLibraryPlatform` decodes cover art with Skia and scales it down.
- **Platform hooks:** reduce motion, Back handling, and the wallpaper accent (Android 12+) are `expect`/`actual`.

**Dependencies:**
- **Shared Android code** now uses `androidx.activity:activity-compose` 1.13.0 for Back handling. It was already in the version catalog and used by the app.
- **Shared desktop tests** now use `compose.desktop.currentOs`, the same Compose runtime the desktop app already ships, to render screens off-screen.
- No new libraries, no version changes.

**Version control:** prior copies were stored before editing:
- `version-control/shared/2026-10-29_2034/`:
  - `App.kt` (removed, replaced by `PhoneApp`/`DesktopApp`);
  - `LibraryDatabase.kt`, `LibraryScanner.kt`, `TagReader.kt`, `SafFolderLibrarySource.kt`;
  - `MusicPlayer.kt`, `DesktopPlayer.kt`, `GstPlayer.kt`;
  - `build.gradle.kts`.
- `version-control/androidApp/2026-10-29_2034/`: `AndroidManifest.xml`, `MainActivity.kt`, `AndroidPlayer.kt`.
- `version-control/desktopApp/2026-10-29_2034/`: `main.kt`.

## Verification

- All modules compile, including both Android APKs.
- **`:shared:jvmTest`: 45/45 pass** in the Bubblewrap sandbox (read-only system, repo hidden, no network). 9 are new:
  - 7 app-model tests:
    - first-run scan, folder saving and queueing;
    - empty or unreadable folders not saved;
    - later launches;
    - the play button;
    - saving accent choices;
    - malformed settings;
    - **upgrade of a version-1 database**, with tracks kept.
  - 2 desktop screenshot tests: the real UI rendered off-screen for first run (welcome, scanning, done) and Home at 1440, 1100 and 860 wide.
- **Android, on the emulator (sandboxed, no network):** the real app went through the whole flow.
  - Steps: the welcome screen, then Android's own folder picker (Music), then the real "Allow access" prompt, then the scan (4 songs · 4 albums · 4 artists), then **Start listening**, the disc home, and Now Playing.
  - **Play** started real playback, with cover art on the disc and the seek bar moving. The system media session showed PLAYING and moved on to the next song by itself.
  - Swiping left returned home with the disc spinning. Library showed the mini-player, and Back returned home.
  - No crashes from the app.
- **Android player tests: 5/5 pass** on the emulator (`OK (5 tests)`): shuffle, every format, repeat, pause/seek/next/previous, and broken-file skipping.
  - **Animations must be off on the test device.** These tests open the app's screen so background playback may start. That screen is now the real UI, whose disc spins while music plays. Android's test framework waits for the screen to go idle before closing it, so with animations on, the repeat test (music still looping) waited forever.
  - **The fix is in the test setup:** the emulator script sets `animator_duration_scale 0`, the usual practice for instrumented UI tests. The app already stops its motion under that setting. No app code changed.
- **Test music** was made in the sandbox: four 30-second FLAC tones whose tags and cover art match the mockups' sample songs, written with a standard-library-only script. Nothing was downloaded.
- **Comparison page** (local, gitignored): `project-notes/ui-progress.html`, with screenshots in `project-notes/screenshots/build/part3-step1/`.

## Impacts

- **The real UI replaces the placeholder** on both platforms. First run appears until a folder with music has been scanned.
- **Android permissions are unchanged.** There's still no storage permission (only the chosen folder) and no internet permission.
- **Dynamic colour is on by default** (as in the mockup), so on Android 12+ the accent follows the wallpaper. On the emulator that's lavender.
- **Known gaps** (next steps):
  - the Library list;
  - Settings (accent picker, rescan, folder change);
  - clicking queue rows;
  - "Resume on start";
  - **light theme**, which needs a mockup first (the mockups are dark only).
- No environment variables, secrets or PII.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User)

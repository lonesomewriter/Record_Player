# Record Disc UI, Step 2: Library (Part 3)

- **Time:** 2026-10-29 22:41 UTC (Songs tab, queue clicks, notification tap) to 2026-10-30 01:30 UTC (Albums/Artists, blink, `web-configs.json`)
- **Change:** Built the Library on both platforms to the approved mockups: Songs, Albums and Artists tabs, album and artist pages, search, and picking a specific song. Also made the Android media notification open the app, made the "Local" light blink slowly, and added `web-configs.json` for animation timings.

## Details

**Library (shared Compose code, `ui/Library.kt`):**
- **Search** across titles, artists, albums and file names (`%` and `_` are matched literally). All three tabs follow the search.
- **Songs:** tap a song to play the list from that song. Android then opens Now Playing; desktop highlights the playing row. The desktop header has **Play all**.
- **Albums** (PM chose a cover grid): two covers across on Android, as many as fit on desktop (cards at least 160 dp wide), sorted by name, with "artist · year" under each.
  - **Album page:** cover, name, artist (tap to open the artist), "year · N songs · M min", **Play** and **Shuffle**, and the songs in disc and track order with track numbers.
- **Artists:** a list with round pictures on Android, and a grid of round pictures on desktop, with "N albums · M songs".
  - **Artist page:** picture (the newest album's cover), counts, **Play all** and **Shuffle**, an Albums row (newest first), then all songs with their album.
- **Grouping rules** (`library/LibraryGroups.kt`):
  - An album is its album name plus album artist (or artist when there's no album artist). Two artists' "Greatest Hits" stay apart, and compilations tagged with an album artist stay together.
  - Songs without an album tag appear under Songs and on their artist's page, but not under Albums. Songs without any artist don't appear under Artists.
- **Back** on Android closes the album or artist page first, then the Library.
- **⋮ menu** on songs lists Add to playlist, Edit metadata and Edit lyrics as "SOON".
- **Cover cache** (`ArtworkCache`): now also limited to about 48 MB of pixels, as album pages and grids load larger covers.

**Player:**
- **`skipTo(index)`:** clicking a song in the desktop "Up next" queue jumps to it. It follows the shuffled order when shuffle is on.
- **`AppModel.playAll(tracks, shuffle)`:** Play turns shuffle off and starts at the first song. Shuffle turns it on and starts on a random song.

**Android notification:** tapping the media notification now opens the app on Now Playing.
- **Mechanism:** Media3 session activity, an explicit `PendingIntent` with `FLAG_IMMUTABLE`, and `MainActivity` set to `singleTop` so the existing screen is reused.

**"Local" light:** the dot beside "LOCAL" (Android) and "LOCAL LIBRARY" (desktop) fades to 25% and back, with its glow, as in the mockups.
- **Reduced motion:** it stays lit when the system asks for less motion.

**`web-configs.json` (new, repo root):**
- **What it holds:**
  - `localLight.blinkSeconds`: 2.4, allowed range 0.5–10;
  - `disc.spinUpMs`: 300, allowed range 0–10000;
  - `disc.spinDownMs`: 600, allowed range 0–10000.
- **How it's used:**
  - A Gradle task (`:shared:generateWebConfigs`) turns the file into Kotlin constants at **build time**.
  - The app never reads the file at run time and never fetches anything. It still has no internet permission.
  - A missing or out-of-range value stops the build with a message naming the key.
  - `0` for the disc timings means instant start or stop.
  - The README documents the keys.

## Version control

Prior versions are stored as follows:
- `version-control/shared/2026-10-29_2241/`: `AppModel.kt`, `DesktopApp.kt`, `DesktopPlayer.kt`, `MusicPlayer.kt`, `PhoneApp.kt`, `PlaybackQueue.kt`.
- `version-control/androidApp/2026-10-29_2241/`: `AndroidPlayer.kt`, `PlaybackService.kt`, `MainActivity.kt`, `AndroidManifest.xml`.
- `version-control/shared/2026-10-30_0053/`: `Controls.kt`, `Disc.kt`, `FirstRun.kt`, `ArtworkCache.kt`, `build.gradle.kts`.

New files with no prior version:
- `ui/Library.kt`;
- `library/LibraryGroups.kt`;
- `web-configs.json`;
- the new test file.

## Tests

- **Desktop (`:shared:jvmTest`, sandboxed, offline): 56/56 pass.** New tests:
  - **5 grouping tests:** track order, same-named albums by different artists, album artists, artist album order and loose songs, and covers;
  - **2 app-model tests:** albums and artists follow the search; Play and Shuffle set shuffle and start playback;
  - **a skipTo test** for the queue and for the desktop player;
  - **library search;**
  - **screenshots 07–14:** Library, narrow, search, Albums, album page, Artists, artist page, narrow Albums.
- **Config check:** on a scratch copy, an out-of-range value and a non-number each stopped the build with the key's name. Changed values were compiled in.
- **Android, on the emulator (sandboxed, no network):**
  - Full first run, then Library. Checked:
    - the Albums grid;
    - the album page with its songs;
    - the artist link;
    - Back to the album, then to the Library;
    - the Artists list;
    - an artist's **Play all**, which opened Now Playing on that artist's song.
  - The notification tap opened the app on Now Playing.
  - No app crashes.
  - **Fix found on the emulator:** the phone album card's rounded clip cut off the first letter of its subtitle. The card is no longer clipped.
- **Android player tests: 5/5 pass** (`OK (5 tests)`).
  - **Emulator shutdowns:** in two earlier runs, the emulator shut down partway through the tests. Leftover Gradle build processes were holding about 2 GB of the machine's 8 GB. With them closed, the tests passed.
  - **New test step:** the test script now stops Gradle before starting the emulator and keeps a live system log.

## Impacts

- **Library:** Albums and Artists replace the "being mocked up" placeholders.
- **Tagging:** songs need album and artist tags to appear under those tabs, and the empty tabs say so.
- **Animation timings** can be tuned in `web-configs.json` and take effect on the next build.
- **Permissions are unchanged:** no internet, no storage permission (only the chosen folder).
- No environment variables, secrets or PII.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User): approved building Albums/Artists as mocked up and the blink as is, and requested `web-configs.json` for the blink speed and the disc momentum.

# Record Disc UI, Step 3: Settings (Part 3)

- **Time:** 2026-10-30 01:59 UTC to 03:10 UTC
- **Change:** Built Settings on both platforms to the approved mockups, with every setting working.
- **PM decisions:**
  - The Theme row offers Dark only. System and Light are marked "SOON" until the light theme is mocked up and approved, which is the next step.
  - The three desktop-only extras (Gapless playback, Watch for changes, Pause on headphone unplug) are built now.

## Details

**Settings screens (`ui/Settings.kt`):**
- **Android**, as in the mockup:
  - Appearance: Theme, Accent color (6 presets plus custom), Dynamic color;
  - Library: Music folder with Change, Rescan;
  - Playback: Resume on start, Pause on headphone unplug.
- **Desktop**, as in the mockup: cards for Appearance, Library, Playback and About, in two columns, or one column under 900 wide.
  - Library adds Watch for changes. Playback adds Gapless playback.
  - About shows the app version, the library's file formats, and "No account, no telemetry · OFFLINE".
  - **Dynamic color** is marked SOON on desktop. Linux has no standard system accent the app can read without extra system services.
- **Custom accent dialog** (both platforms): a saturation/brightness square, a hue bar, a preview with the hex value, Cancel and Apply. The colour is saved and applied at once.
- **Accent and dynamic colour on Android:** picking a preset or custom accent turns dynamic colour off, so the choice takes effect. Phones before Android 12 show Dynamic color as unavailable.

**Library settings (`AppModel`):**
- **Change folder** uses the system folder picker, then rescans. The library is replaced only if the new folder has music. Otherwise a message says the library wasn't changed.
  - On Android, access to the old folder is given back to the system.
- **Scanner safety rule:** a scan that finds no audio files at all leaves the library untouched, whether that's a folder change, a rescan or the background rescan at launch. An empty listing is far more often an unplugged drive or the wrong folder than music really deleted.
- **Rescan** shows a live count on its button, then "Library updated · N songs".
- **Watch for changes** (desktop, `FolderWatcher`) uses Java's built-in file watching on the music folder and its subfolders.
  - Folders added later are watched too. Hidden folders are skipped, as in the scanner.
  - A quiet rescan runs 2 seconds after changes stop.
  - Turning it off stops watching.

**Playback settings:**
- **Resume on start:** the queue (in play order), the current song and its position are saved in the settings table.
  - The position is saved every 5 seconds while playing, and exactly on pause or seek.
  - At launch the app reopens there, paused. Songs removed from the library since are left out.
  - When off, the library is queued from the top, as before.
- **Pause on headphone unplug:**
  - **Android:** Media3's "audio becoming noisy" handling, now switched on or off by the setting.
  - **Linux (`OutputMonitor`):** GStreamer's device monitor (PipeWire/PulseAudio) pauses when the default output disappears, such as Bluetooth or USB headphones disconnecting.
  - **Limits:** a wired headphone jack usually changes ports on the same output instead, which this doesn't detect. The Codespace has no sound card, so only clean start-up and shut-down could be tested.
- **Gapless playback (desktop):** GStreamer's `about-to-finish` signal hands the next song to playbin shortly before the current one ends, so there's no silence between songs. The queue follows when the next song starts.
  - Repeat-one hands over the same song.
  - At the end of the queue nothing is handed over, and playback stops as before.
- **Start position:** `MusicPlayer.setQueue` takes a start position, so a restored queue opens at the right place in one command.

**Fixes found by testing:**
- **Seeks after a gapless handover** were ignored by GStreamer. Once the next song is queued, a seek now reloads the current song first. In normal use this only affects seeks in a song's last few seconds.
  - **How it was found:** the "Previous restarts the song" test failed 4 times out of 5. It now passes every time.
- **Android delayed seeks:** a seek made while a song was still loading could later land on a different song, if another controller loaded one in between. It's now applied only to the song it was meant for.
  - **How it was found:** an Android player test failed after the app's own Resume on start ran first.

## Version control

Prior versions are stored as follows:
- `version-control/shared/2026-10-30_0159/`: `AppSettings.kt`, `AppModel.kt`, `LibraryScanner.kt`, `DesktopPlayer.kt`, `GstPlayer.kt`, `DesktopLibraryPlatform.kt`, `AndroidLibraryPlatform.kt`, `PhoneApp.kt`, `DesktopApp.kt`, `Controls.kt`, `PlaybackQueue.kt`, `MusicPlayer.kt`.
- `version-control/androidApp/2026-10-30_0159/`: `PlaybackService.kt`, `AndroidPlayer.kt`.
- `version-control/desktopApp/2026-10-30_0159/`: `main.kt`.

New files with no prior version:
- `ui/Settings.kt`;
- `library/FolderWatcher.kt`;
- `playback/OutputMonitor.kt`;
- the new test files.

## Tests

- **Desktop (`:shared:jvmTest`, sandboxed, offline): 68/68 pass.** New tests:
  - **Resume:** Resume on start restores the song and position, paused; with it off, the queue starts at the top.
  - **Folder change:** an empty folder leaves the library alone; a new folder replaces it and releases the old one.
  - **Watching:** the watched folder rescans after changes; watching stops when the setting is turned off. `FolderWatcher` sees files added, changed and removed, including in new subfolders, and nothing after closing.
  - **Gapless:** the switch to the next file comes with no end of stream between files; the queue follows the audio; with gapless off, playback still goes through the queue.
  - **Other:** the scanner's empty-folder rule, `peekNext`, starting a queue part-way into a song, and the output monitor starting and stopping cleanly.
  - **Screenshots 15–18:** Settings, narrow Settings, the custom accent dialog, and Settings with a custom accent.
- **Android, on the emulator (sandboxed, no network):**
  - **Walk-through, all checks passed:**
    - Settings opens, with dynamic colour on by default;
    - picking Coral selects it and turns dynamic colour off;
    - the custom accent dialog opens and Apply saves "Custom";
    - Rescan reports "Library updated";
    - **Resume on start:** paused mid-song, force-stopped, reopened on the same song;
    - no app crashes.
  - **Player tests: `OK (6 tests)`**, including the new start-position test.
  - **Test setup fix:** the emulator scripts now clear the app's data before the player tests, as the test class requires. Without that, the app's own Resume on start restored its saved queue on the shared playback service in the middle of a test, which made `shuffleKeepsTheCurrentSong` fail intermittently. The test also now waits for the shuffle order to settle and names the expected song when it fails.

## Impacts

- **New settings** stored locally: `gapless` and `watchFolder`, plus the saved queue for Resume on start. No database schema change, since settings are name/value rows.
- **Library protection:** a folder with no music no longer clears the library during any scan.
- **Permissions are unchanged:** the app requests only foreground media playback and wake lock, plus an app-internal one. No internet, no storage permission.
- No new dependencies, environment variables, secrets or PII.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User): "commit, then let's continue with what we have left". Chose "Build Settings, Light later" and "Build now" for the desktop extras.

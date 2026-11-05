# "Open with" from the file manager, and "View Location" (app)

**Time:** 2026-11-04, 21:56–22:25 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "mockups look good, let's go to implementation". The behaviour was decided earlier, in `open-with-mockup.md` and `view-location-rename-mockup.md`.

## What changed for users
- **Song ⋮ menu (Android and desktop):** "View file location" is now **View Location**, second after View Details. The order is: View Details, View Location, Edit lyrics, Edit metadata, Add to playlist (then Remove from playlist inside a playlist).
- **Open with (Android):** Record Player appears in a file manager's "Open with" list for audio files. It's listed but never made the default, unless you pick "Always".
- **Open with (Linux desktop):** the .deb's menu entry lists the audio file types, so file managers show Record Player under Open With. If Record Player is already open, the file goes to that window, which comes to the front; no second window opens. With several files selected, the first one plays.
- **A file that's in the library** plays as if chosen in Library, with the library as the queue and every ⋮ action available.
  - Android matches by name and size, because file managers hand over their own address.
  - Desktop matches by path.
- **Any other file plays once, on its own.**
  - It's marked **"Opened from Files · not in your library"** on Now Playing (phone) and Home (desktop).
  - View Location, Edit lyrics, Edit metadata and Add to playlist are greyed out under a note saying why. The lyrics panel's edit links are hidden.
  - Desktop shows Track details → Source as "Opened from Files".
  - It isn't added to the library and isn't saved for Resume on start, so the queue saved before it comes back next time. Once something else plays, it's forgotten.
- **Desktop menu entry:** it's now named "Record Player" (was "record-player") and listed under Audio/Video (was `Categories=Unknown`, which fails `desktop-file-validate`).

## Files
Prior versions of the changed files are in:
- `version-control/shared/2026-11-04_2156/` (AppModel, TrackEntity, Library, PhoneApp, DesktopApp, LyricsPanel, SongDetails, DiscIcons, AndroidLibraryPlatform, DesktopLibraryPlatform)
- `version-control/androidApp/2026-11-04_2156/` (AndroidManifest.xml, MainActivity.kt)
- `version-control/desktopApp/2026-11-04_2156/` (build.gradle.kts, main.kt)
- `version-control/scripts/2026-11-04_2216/` (build.sh)

### Shared code
- `library/TrackEntity.kt`: `OUTSIDE_TRACK_ID` (-1) and `TrackEntity.isOutside`.
- `app/AppModel.kt`:
  - `OpenedFile`, `OpenedAs`, `findLibraryMatch()` (same path; otherwise the same name, ignoring case, and size, but only when exactly one song matches).
  - `AppModel.openFile()`:
    - It waits until the startup queue is set, so Resume on start can't replace the opened file.
    - It reads tags through the new `LibraryPlatform.readFileTags()`. If they can't be read, it plays under the file name.
  - `outsideTrack`.
  - Resume on start skips any queue containing the outside file.
- `ui/Library.kt`: the `SongMenu` rename and order, the outside-file note and greyed-out items, and the `OUTSIDE_FILE_LABEL` / `OUTSIDE_FILE_NOTE` texts.
- `ui/PhoneApp.kt`:
  - `PhoneApp(openedFiles = …)` opens Now Playing.
    - A library song's play source is Library.
    - Leaving Now Playing for an outside file goes Home.
  - `OutsideFileLabel`. `rememberTrackIndex` includes the outside file.
  - An empty album line is no longer drawn.
- `ui/DesktopApp.kt`: `DesktopApp(openedFiles = …)` switches to Home. Home shows the label (in place of "Local file") and the note, Edit metadata is off, and Source shows "Opened from Files".
- `ui/LyricsPanel.kt`: no edit links for an outside file. `ui/DiscIcons.kt`: a `Folder` icon.
- `androidMain/.../AndroidLibraryPlatform.kt` and `jvmMain/.../DesktopLibraryPlatform.kt`: `readFileTags()`, using the same readers as library scans.
- **New:** `jvmMain/.../app/SingleInstance.kt`.
  - The desktop one-window hand-over uses a Unix socket at `$XDG_RUNTIME_DIR/record-player.sock` (falling back to the app's data folder), with owner-only permissions. It's local only, never the network.
  - A stale socket left by a crash is replaced.
  - `openArgumentsToPaths()` accepts paths or `file://` URIs that point to existing files.

### Android
- `AndroidManifest.xml`: a `VIEW` intent filter for `content:` URIs with `audio/*` and `application/ogg` (some file managers label .ogg/.opus that way).
  - **No new permission:** the file manager grants read access to the one file.
- `MainActivity.kt`: reads the file's name and size (`OpenableColumns`), then calls `model.openFile()` and opens Now Playing. If the file can't be opened, it says "Couldn't open <name>".

### Desktop
- `main.kt`:
  - `main(args)` claims the single instance, or hands the paths over and exits.
  - Opened files play once the startup queue is set.
  - The window comes to the front (un-minimising it), and the socket is removed on close.
- **New:** `desktopApp/packaging/linux/record-player.desktop`.
  - Name "Record Player"; `Categories=AudioVideo;Audio;Player;`.
  - `Exec=/opt/record-player/bin/record-player %F`.
  - MimeType: MP3, FLAC, M4A/AAC, Ogg/Vorbis, Opus and WAV, under their common names.
  - Passes `desktop-file-validate` with no errors or warnings. jpackage's generated entry failed it (`Categories=Unknown`).
- `build.gradle.kts`: after `packageDeb` / `packageReleaseDeb`, the .deb is unpacked (`dpkg-deb --raw-extract`), the generated entry replaced, and the package rebuilt with `dpkg-deb --root-owner-group`.
  - The entry keeps the same file name, which jpackage's install script registers. Its mode is set to rw-r--r--.
  - **Why this route:**
    - jpackage's own file-association option installs its own MIME definitions for these standard types.
    - The Compose task clears the resource folder it hands jpackage, so an override placed there is lost (tried, with an extra `--resource-dir` too).
  - **Checked:** the rebuilt .deb's file list, permissions and ownership are identical to the 19:00 build's (only the entry's content and the app jars differ). The control files and install scripts are unchanged.
- `scripts/build.sh`:
  - New .deb checks:
    - menu entry paths (Exec and Icon are shipped)
    - Open with (MimeType lists the audio types, and Exec passes `%F`)
    - `desktop-file-validate` (when installed; otherwise it says it was skipped)
  - The headless start check now also gets a throwaway `XDG_RUNTIME_DIR`, so its socket never touches a real session.

## Tests
- **New** `OpenFileTest` (4):
  - matching (path; name and size; size mismatch; two candidates; no size)
  - a library song plays with the library queue
  - an outside file plays alone, isn't added, leaves the saved Resume queue unchanged, and is forgotten after a library song plays
  - unreadable tags fall back to the file name
- **New** `SingleInstanceTest` (3):
  - a second start hands over paths (including non-English characters) and an empty start
  - the socket is removed on exit, and a stale socket is replaced
  - argument parsing
- **New** `DesktopScreenshotTest.anOpenedOutsideFileIsMarkedAndPlayOnly`:
  - Desktop switches from Library to Home with the label and note, Edit metadata off and no "Local file".
  - Phone Now Playing shows the label and "return to Home"; the ⋮ menu has the note and the four items off.
  - Screenshots: `shared/build/screenshots/desktop/45-opened-outside-file.png`, `shared/build/screenshots/phone/phone-opened-outside-file.png`.
- `songMenusSongDetailsAndNewSettings` was updated for the new menu order and name.
- **Results:**
  - Passed: `AppModelTest` (32), the new tests, and `songMenusSongDetailsAndNewSettings`, `playlistDialogs` and `prototypeLongPressReachesTheHook`.
  - `:androidApp:compileDebugKotlin` and `:desktopApp:compileKotlin` pass.

## Impacts
- No new dependencies, permissions, environment variables or network use. The desktop socket is local and owner-only.
- `desktop-file-validate` isn't installed in this Codespace. It was run from the `desktop-file-utils` package downloaded with `apt-get download` into the session's scratch folder (nothing installed system-wide).
- **Not testable here:** a real file manager's Open With on Android (the project manager's phone) and on a Linux desktop (install the new .deb). Check both before relying on them.
- Not committed.

## Dev builds (22:17 UTC)
`scripts/build.sh dev` passed all checks, including the new ones: menu entry paths, Open with, and `desktop-file-validate` (no errors).
- `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_2217/RecordPlayer-android-0.1.0-dev.apk`. It still contains the TEMPORARY convert prototype.
- `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-04_2217/record-player_0.1.0_amd64.deb`

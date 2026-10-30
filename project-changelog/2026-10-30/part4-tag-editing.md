# Part 4: Tag editing (song details editor)

- **Time:** 2026-10-30 15:12 UTC to 18:34 UTC
- **Change:** Songs' details (title, artist, album, album artist, year, track, genre, cover art) can now be edited on both platforms and are saved into the song files themselves, safely.
- **PM decisions:**
  - Fields: the approved mockup's six, plus **Album artist** (the Library groups albums by it).
  - Cover: **Choose cover image** (JPEG/PNG, up to 10 MB) and **Remove cover**.
  - Android, editing the song that is playing: **save, then resume at the same spot**.
  - The editor's extra states (Allow editing, read-only, invalid input, save failed, saving) were mocked up first. The PM approved them before the screens were built.

## Details

**What the user sees:**
- **Android:**
  - Now Playing **⋮ → Edit metadata**, or a song's **⋮** in the Library, opens a full-screen editor.
  - A folder granted before Part 4 is read-only. The editor then shows **Allow editing**, which reopens Android's folder picker on the same folder and keeps write access.
- **Desktop:** the **Edit metadata** button on Home (no longer "SOON"), or a song's **⋮** in the Library, opens a pop-up. Esc, ✕ or clicking outside closes it.
- **Fields:**
  - A blank field removes that detail from the file.
  - Year must be 4 digits and Track 1–999. Otherwise red text appears under the field and Save is disabled.
- **Saving:**
  - Save shows "Saving…".
  - Success shows "Song details saved", and the library, queue, cover art and Android notification update straight away.
  - A failure shows the reason in a red box, and the typed values stay.
- **Files that can't be changed:** they open read-only, with the reason (no permission, or tags that couldn't be read).

**How it saves (`shared/…/tags/`, `library/`):**
- **`TagWriter`** writes the fields with jaudiotagger 3.0.1 (MP3, FLAC, OGG, M4A, WAV) and with our `OpusTags` (Opus).
  - **Cover art** is built from the image bytes and the size the app already knows. For FLAC, Ogg and Opus it builds the picture block itself, because jaudiotagger's own path for those needs java.awt, which Android lacks.
  - **Verification:** after writing, the tags are read back and compared. A mismatch cancels the save.
- **Linux (`DesktopTagSaver`):**
  1. The edit is made on a hidden copy next to the song.
  2. The copy is synced to disk, and the original file's permissions are kept.
  3. It replaces the song in one atomic rename.

  A player that has the old file open keeps playing it.
- **Android (`BackupThenOverwrite`, Storage Access Framework):**
  1. Build and check the new file in app storage.
  2. Back up the original and write a pending-save note.
  3. Overwrite the song in place.
  4. Compare SHA-256 hashes.
  5. Delete the backup and the note.

  If the app dies mid-save, the next launch keeps the finished file or puts the original back. It needs free space for about two copies of the song.
- **After a save (`AppModel.saveTags`):**
  - **Database:** the song's row is updated (same id, new size and modified time, so later rescans don't re-read it).
  - **Cover cache:** it forgets the old cover (`ArtworkCache.invalidate`).
  - **Queue:** queued copies are updated (`MusicPlayer.updateItem`).
  - **Android:** if the edited song is playing, it pauses for the save, then reopens the file at the same spot.
  - **Scans:** scans wait for a save, and a save waits for a running scan.
- **Android folder access (`MainActivity`):** new folder picks keep read and write access. Releasing a folder releases both.

**Bugs found and fixed while testing:**
- **Start-up rescan:** `AppModel` declared its save lock after the code that starts the start-up rescan, so the rescan could hit a missing lock. Caught by an AppModel test.
- **First save on a phone:** the free-space check looked at the app's `tag-edit` folder before it existed, and a missing folder reports 0 bytes free. Every first save on a phone was refused with "Not enough free space". Caught by the emulator walk-through; a test now covers it.
- **Covers on Android:** cover art crashed on Android for FLAC/Ogg/Opus (jaudiotagger's java.awt path). Caught by the new Android test.
- **Older Android versions:** an API-33-only call (`readNBytes`) in the Android cover picker was replaced, since the app supports Android 8+.

**Other:**
- `PrimaryButton` gained a disabled state.
- `DiscColors.error` (error text) was added to both themes.
- Desktop and Android cover pickers accept JPEG/PNG only.
- New test tones `tone.m4a` and `tone.wav` were made with GStreamer.
- The light-theme changelog's end time was corrected to the real 15:10 UTC; it had been a guessed 15:40.

## Version control

Prior versions are stored as follows:
- `version-control/shared/2026-10-30_1526/`: AndroidLibraryPlatform.kt, AppModel.kt, AppModelTest.kt, ArtworkCache.kt, DesktopLibraryPlatform.kt, DesktopPlayer.kt, LibraryScanner.kt, MusicPlayer.kt, PlaybackQueue.kt, TagReader.kt, TestFakes.kt.
- `version-control/shared/2026-10-30_1608/`: DesktopApp.kt, DesktopScreenshotTest.kt, FirstRun.kt, Library.kt, PhoneApp.kt, Platform.android.kt, Platform.jvm.kt, Platform.kt, Theme.kt, ThemeTest.kt.
- `version-control/androidApp/2026-10-30_1526/`: AndroidPlayer.kt, AndroidPlayerTest.kt, MainActivity.kt.
- `version-control/desktopApp/2026-10-30_1608/`: main.kt.

New files with no prior version:
- `library/TagEdit.kt`, `ui/TagEditor.kt`, `tags/TagWriter.kt`, `tags/BackupThenOverwrite.kt`, `library/DesktopTagSaver.kt`;
- the tests `TagWriterTest`, `DesktopTagSaverTest`, `BackupThenOverwriteTest` and `TagWriterAndroidTest`;
- the tones `tone.m4a` and `tone.wav`.

## Tests

- **Desktop (`:shared:jvmTest`, sandboxed, offline): 90/90 pass.** Runs `final4a` (87) and `final4b` (3 screenshot tests). The suite runs in two batches because something outside the sandbox stops long single runs on this Codespace; the details are in the handout.
  - **`TagWriterTest`**, for MP3, FLAC, OGG, Opus, M4A and WAV:
    - every field and the cover are written and read back;
    - blank fields and a removed cover take the tags out;
    - other tags (e.g. composer) and the cover are kept;
    - the length is unchanged;
    - GStreamer still plays every edited file.
  - **`DesktopTagSaverTest`:**
    - the atomic swap leaves no temp files and keeps permissions;
    - read-only files and folders are refused, with the file unchanged;
    - a failed edit leaves the original byte-for-byte.
  - **`BackupThenOverwriteTest`:**
    - a normal save;
    - the first save before the work folder exists;
    - a failed overwrite is undone at once;
    - an app "killed" mid-overwrite is restored at the next launch;
    - a finished overwrite is kept;
    - too little space is refused before anything changes.
  - **`AppModelTest`:**
    - a save updates the library (same id), the queue and the cover cache;
    - an in-place save of the playing song resumes it;
    - a failed save returns the reason and changes nothing;
    - a later rescan doesn't re-read the file.
  - **Theme:** error text reads at 4.5:1 or more in both themes.
  - **Screenshots 27–30:** the editor, the narrow window, the light theme and read-only.
- **Android, on the emulator (sandboxed, no network), run `edit10`:**
  - **Walk-through.** It installs the Part 3 app first, so the folder grant is read-only, then upgrades to this build:
    - **Allow editing** is shown, and choosing the same folder unlocks editing;
    - an invalid year shows its error;
    - title, album artist and year are saved for the **playing** song, which was PLAYING before Save and PLAYING after;
    - Now Playing and the notification show the new title;
    - **Remove cover** works;
    - after a force-stop and relaunch, the Library shows the new title;
    - the song file pulled from the device has the new tags and no picture block;
    - no app crashes.
  - **Instrumented tests: `OK (8 tests)`.** The 6 player tests, plus:
    - `TagWriterAndroidTest`: fields and cover on all six formats in jaudiotagger's Android mode;
    - `reloadAfterARewriteKeepsPlayingAtTheSameSpot`.
- **Not covered by the walk-through:** choosing a cover through Android's own file picker. The script couldn't reliably get through the system picker's folders. Cover writing on Android is covered by `TagWriterAndroidTest`, and on desktop by `TagWriterTest`.
- **Found by the emulator runs, fixed and re-tested:**
  - the first-save free-space bug;
  - covers on Android for FLAC/Ogg/Opus;
  - **playback not resuming after an in-place save:** the Android player compared the current index right after replacing the song's details, which can briefly report another index. It now notes the current song and position before replacing.

## Impacts

- **Song files can now be changed** by the app, only when the user saves in the editor. Each save is checked after writing and is never half-written.
- **Android:** the app now holds **write** access to the chosen music folder. Existing installs keep read-only access until the user taps **Allow editing**. There is still no storage permission and no internet permission.
- **Android disk space:** saving uses temporary app storage for about two copies of the song, and the copies are deleted after the save.
- **No database schema change.** No new dependencies (jaudiotagger 3.0.1 was already used for reading), environment variables, secrets or PII. `.gitignore` is unchanged.
- **LGPL:** jaudiotagger is still LGPL 2.1; its distribution obligations are part of Part 6.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User):
- "Then get to work on Part 4" and the approved implementation plan.
- Chose: Album artist field; Choose + Remove cover; save-then-resume on Android; mockups first.
- "The mockups look good, go ahead and build the screens."

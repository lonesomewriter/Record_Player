# Part 5a: Lyrics — Steps 1–2 (data, no screens yet)

- **Time:** 2026-10-30 18:47 UTC to 19:08 UTC (checkpoint commit; the screens follow as Step 3)
- **Change:** Everything lyrics need behind the screens: reading, writing and finding `.lrc` files next to songs, reading lyrics stored inside song files, the remembered Lyrics toggle, and a smooth playback position for following lyrics. Nothing visible changes in the app yet; Lyrics still shows "SOON".
- **PM decisions:**
  - Lyrics first, then Playlists. The `.rdplaylist` bundle mockup is approved. "Convert to one audio file" waits for research.
  - Lyrics inside a song are shown when there's no `.lrc`. Editing saves an `.lrc`, and the song file is never changed.
  - Any song's lyrics can be edited, and the editor's play button starts that song.
  - The toggle is remembered.
  - Leaving with unsaved changes asks "Discard changes?".
  - The lyrics mockups were approved as they are.

## Details

- **`library/Lrc.kt` (new):** reads and writes `.lrc` text.
  - UTF-8 with or without a byte-order mark; Windows-1252 for older files.
  - Timestamps `[mm:ss]`, `[mm:ss.xx]` and `[mm:ss.xxx]`, and several on one line.
  - `[offset:]` is applied. Word-level timing is removed. Header tags such as `[ar:]` are kept.
  - Untimed lines are written as plain text in their place.
  - Also: sorting for save (an untimed line stays after the line before it), the active line at a position, and `mm:ss.xx` times.
- **Database version 3:** each song gains `folder` and `lyricsLocation` (automatic migration from v2; schema `shared/schemas/.../3.json`).
  - The scanners match `<song name>.lrc` in the same folder, ignoring case, on desktop and Android.
  - Adding or removing an `.lrc` updates the song without reading its tags again.
- **`TagReader.readLyrics`:** reads lyrics stored inside MP3, FLAC, OGG, Opus and M4A files. On Android it reads through the open file's descriptor, so the song isn't copied; a temporary copy is the fallback.
- **Saving lyrics:**
  - **Linux (`DesktopLyricsSaver`):** a hidden copy is written and synced, then swapped in atomically. Deleting removes the file.
  - **Android (SAF):**
    - An existing `.lrc` is overwritten, read back to check, and restored from memory if the check fails.
    - A new one is created in the song's folder. If Android would rename it (e.g. add ".txt"), it's deleted and the save fails with a message.
    - Deleting removes the document.
- **`AppModel`:**
  - lyrics are cached per song, with a revision that refreshes the screens;
  - `saveLyrics` sorts the lines and drops empty ones; it removes the `.lrc` when nothing is left, keeps the header tags of an existing `.lrc`, and shows "Saved <name>.lrc · N not timed";
  - `setShowLyrics` (a new setting, `showLyrics`, off by default);
  - `playForEditing`.
- **`ui/SmoothPosition.kt` (new):** moves the position forward between the players' 200–250 ms updates while playing, up to 1 second ahead.

## Version control

- Prior versions are in `version-control/shared/2026-10-30_1856/`: TrackEntity, TrackDao, LibraryDatabase, LibraryScanner, AppModel, AppSettings, FolderLibrarySource, SafFolderLibrarySource, TagReader, DesktopLibraryPlatform, AndroidLibraryPlatform, TestFakes, AppModelTest, LibraryScannerTest.
- New files: `Lrc.kt`, `DesktopLyricsSaver.kt`, `SmoothPosition.kt`, `LrcTest`, `DesktopLyricsSaverTest`, `EmbeddedLyricsTest`.

## Tests

- **Desktop (`:shared:jvmTest`, sandboxed, offline), all non-screenshot tests pass.** New and changed:
  - `LrcTest` (8): timestamp forms, BOM/CRLF/Windows-1252, offset and repeated timestamps, word timing and tags, untimed lines, round trip, active line, time format.
  - `LibraryScannerTest`: `.lrc` matching and following.
  - `DesktopLyricsSaverTest` (2): create, overwrite and delete with no temp files; a read-only folder is refused.
  - `EmbeddedLyricsTest`: five formats, including the extension-less path used on Android.
  - `AppModelTest` (+3): save, sort and remove; the toggle is remembered; play for editing.
  - The v1→v3 migration is covered by the existing upgrade test.
- **The Android code compiles.** Android runtime checks (creating and deleting SAF documents, the descriptor read) come with the Step 3 emulator run.

## Impacts

- **Database upgrade:** v2→v3 runs automatically and adds two empty columns. The next scan fills them in.
- **Android:** the app may now create, overwrite and delete `.lrc` files in the music folder, but only when the user saves lyrics, and only with write access.
- No new dependencies, environment variables, secrets or PII. `.gitignore` is unchanged.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User): approved the Part 5a plan and decisions; "Commit steps 1-2 now, and the mockups are fine as-is".

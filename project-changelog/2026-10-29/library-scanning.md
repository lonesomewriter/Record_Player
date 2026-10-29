# Music Library: Scanning and Database (Part 1, "Finding the music")

- **Time:** 2026-10-29 18:41:22 UTC
- **Change:** Added the music library: a Room database of tracks, a scanner that keeps it in sync with the music folder, the desktop folder source, the Android granted-folder source, and an Opus duration reader. No UI yet; verified by tests.

## Details

**Database (shared, Android + Linux):** Room 2.8.5 (stable; Room 3 is still alpha), KSP 2.3.12, `sqlite-bundled` 2.7.1.
- **Bundled SQLite on both platforms** (PM decision), so behaviour is identical everywhere.
- **`tracks` table** (`TrackEntity`):
  - location (path or document URI, unique), file name, format, size, modified time;
  - title, artist, album, album artist, year, track/disc number, genre, duration;
  - has-artwork and tags-readable flags.
- **Queries** (`TrackDao`):
  - live (Flow) track list, albums, artists and search; search treats `%` and `_` literally;
  - scan index, upsert and delete.
- **Schema** exported to `shared/schemas/` so future migrations can be checked.

**Scanner** (`LibraryScanner`, shared):
- reads only new or changed files, judged by size and modification time, and removes files that disappeared;
- files whose tags can't be read stay listed (playable) and are marked `tagsReadable = false`;
- supported: mp3, flac, m4a, ogg/oga, opus, wav.

**Linux** (`FolderLibrarySource`, `DesktopPaths`):
- walks the music folder, skipping hidden files and folders and not following symbolic links;
- the Music folder comes from `~/.config/user-dirs.dirs` (freedesktop XDG), falling back to `~/Music`;
- the database is stored at `$XDG_DATA_HOME/record-disc/library.db` (default `~/.local/share/record-disc/`);
- tags come from `TagReader`: jaudiotagger with the WAV ID3 settings from the stack tests, and `OpusTags` for Opus.

**Android** (`SafFolderLibrarySource`):
- **Scans only the folder the user grants** through the system folder picker (PM decision), so **no storage permission is added** and the manifest is unchanged;
- tags and duration come from Android's built-in `MediaMetadataRetriever`, read through the folder grant;
- the database lives in the app's private storage.

**`OpusTags.durationMs`:** the length comes from the last Ogg page's granule position minus the pre-skip (RFC 7845). Candidate pages must pass their Ogg checksum.

**Version control:** prior copies stored before editing:
- `version-control/shared/2026-10-29_1831/build.gradle.kts`
- `version-control/shared/2026-10-29_1831/OpusTags.kt`
- `version-control/root/2026-10-29_1831/build.gradle.kts`

The version catalog only gained new entries; no existing dependency versions changed.

## Verification

- All modules compile, including the Android debug APK. Room generated the database code for both platforms.
- **`:shared:jvmTest`: 23/23 pass** in the Bubblewrap sandbox (read-only system, repo hidden, no network):
  - 7 library tests:
    - finding files in nested folders while skipping hidden and unsupported ones;
    - reading tags and durations for MP3, FLAC, OGG and Opus;
    - keeping a broken file listed but marked unreadable;
    - rescans with no changes, a changed file and a deleted file;
    - album, artist and search queries.
  - 4 Music-folder detection tests.
  - The existing 7 Opus-tag and 5 playback tests.
- MP3 lengths include up to ~80 ms of frame/encoder padding (measured 2,063 ms for a 2,000 ms tone); the test tolerance reflects that.

## Impacts

- **Android scanning is compiled but not yet run;** that needs an emulator or a phone (part 2).
- Nothing calls the scanner yet. The UI and first-run folder choice come in part 3.
- No new permissions, environment variables, network access, secrets or PII.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User)

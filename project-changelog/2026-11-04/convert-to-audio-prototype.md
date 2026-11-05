# "To audio file" prototype (plan step 1)

**Time:** 2026-11-04, 16:35–19:00 UTC (paused ~17:15–18:55 for the usage limit)
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager. `project-notes/convert-to-audio-plan.md` was approved, and the prototype was requested; the Android check uses a temporary debug button (project manager's choice).

## Details
**Goal:** prove the hard parts before the full build. Songs at different sample rates and layouts are joined into one **FLAC** and one **M4A** file, with the place where each song starts marked, on desktop and Android. No user-facing UI yet.

### New files
- `shared/src/commonMain/.../convert/AudioConversion.kt`: `ConvertFormat` (formats, extensions, rate: Opus 48 kHz, others 44.1 kHz), `ConvertRequest` (gap ≥ 0 enforced), `ConvertResult`, `ConvertCancelled`, and `songStarts()`.
- `shared/src/jvmShared/.../convert/SongMarks.kt`, shared by desktop and Android:
  - `FlacMarks`: writes a CUESHEET block (one track per song) and TITLE/ALBUM tags, and fills STREAMINFO's length when an encoder left it at 0.
  - `Mp4Chapters`: writes Nero `chpl` chapters into moov/udta, and shifts `stco`/`co64` offsets when moov comes before the audio.
  - Both rewrite through a temp file, so a failure can't leave a half-written file, and both have readers for checks.
- `shared/src/jvmMain/.../convert/GstConverter.kt`: the desktop converter. FFM over the system GStreamer, with no binding library.
  - Each song is decoded alone to 16-bit stereo (`uridecodebin ! audioconvert ! audioresample ! appsink`).
  - Its samples, plus silence for gaps, are pushed through `appsrc` into one encoder pipeline.
  - It handles all six desktop formats, reports progress, cancels (deleting the partial file), and reports errors naming the song.
- `shared/src/jvmTest/.../convert/GstConverterTest.kt`: 6 tests.
- `androidApp/src/main/.../convert/MediaCodecConverter.kt`: the Android converter, using `MediaExtractor` + `MediaCodec` with no new library.
  - Each song is decoded alone, mixed down to 16-bit stereo, and resampled with Media3's `SonicAudioProcessor` (already in the app) when its rate differs.
  - The encoders are the FLAC encoder (written to the file directly), AAC + `MediaMuxer` (M4A), and our own WAV writer.
  - The shared mark writers are reused.
- `androidApp/src/main/.../convert/PrototypeConvert.kt`: **TEMPORARY** debug check.
  - It converts a playlist's first 10 songs (1 s gaps) to FLAC and M4A, checks length, marks and tags on the phone, saves both to **Music/Exports** (MediaStore, Android 10+; the app's own Music folder on 8–9), and shows ✓/✗ in a dialog.

### Changed files (backed up first to `version-control/shared/2026-11-04_1702/Playlists.kt` and `version-control/androidApp/2026-11-04_1702/MainActivity.kt`)
- `Playlists.kt`: **TEMPORARY** `PlaylistFileActions.prototypeConvert` (null by default). When it's set, long-pressing the still-disabled "To audio file · SOON" pill runs it.
- `MainActivity.kt`: **TEMPORARY** sets that hook only when the app is debuggable (dev builds). Release builds and desktop never have it.
- **Remove all three TEMPORARY pieces before the full build.**

## Findings
- **GStreamer's `concat` element crashes `flacenc`:** a new segment arrives at each song boundary and the encoder can't drain a part-filled block (abort). This led to the decode-each-song → one-encoder design, which is also the only way to get exact song start positions. Android uses the same structure.
- **FLAC is sample-exact:** three songs (44.1 kHz 24-bit stereo FLAC, 48 kHz Opus, 44.1 kHz mono AAC) plus a 1 s gap decode back to exactly the samples written. The cue sheet offsets equal the computed song starts.
- **AAC adds about 1,012–1,024 samples** of encoder lead-in. That's normal and players skip it.
- **GStreamer reads our FLAC cue sheet** as a table of contents. It can't read MP4 Nero chapters, so the M4A chapters still need an outside check: `ffprobe -show_chapters`, or VLC → Playback → Chapter.
- **Desktop M4A** needs `gstreamer1.0-libav` (present here). Per the plan, it will be offered only when that's installed.

## Verification
- `:shared:jvmTest --tests '*GstConverterTest*'`: 6/6 pass. They cover:
  - FLAC length, cue sheet and tags; M4A chapters (titles, including one with non-English characters)
  - all six desktop formats playable
  - cancel deletes the partial file; a missing song fails with its name; the gap maths
- `DesktopScreenshotTest.playlistDialogs` and `songMenusSongDetailsAndNewSettings` pass, so the desktop playlist page is unchanged.
- `:androidApp:compileDebugKotlin` and `:desktopApp:compileKotlin` pass.
- `scripts/build.sh dev` passes every check; outputs are filed in `project-builds/dev-builds/*_2026-11-04_1900/`.
- **Not run here** (no device): the Android converter. The project manager checks it with the dev APK:
  1. Open a playlist.
  2. Long-press "To audio file · SOON" and read the ✓/✗ dialog.
  3. Play `Music/Exports/<playlist>.flac` / `.m4a`.

## Impacts
- No user-facing change in release builds. Dev APKs gain the hidden long-press.
- No new dependencies, permissions (writing to Music/Exports through MediaStore needs none on Android 10+), environment variables, secrets or network use.
- Not committed.

## Fix: the long press didn't work (19:21 UTC)
- **Project manager report:** long-pressing "To audio file · SOON" did nothing.
- **Causes:**
  - The "SOON" tag sat outside the long-press area, so pressing it did nothing.
  - The long press was detected by a wrapper around a disabled button, which is unreliable and gave no accessibility "long click" action (so it couldn't be tested).
- **Fix** (`Playlists.kt`; prior version in `version-control/shared/2026-11-04_1921/`): the pill and its SOON tag are now one area with Compose's standard `combinedClickable` long-click. A tap still does nothing.
- **New test:** `DesktopScreenshotTest.prototypeLongPressReachesTheHook` triggers the long click and confirms it runs the hook for that playlist, and that there is no long press without the hook. It passes.
- **New dev APK:** `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_1923/` (all checks passed).

## Fix: no result dialog (19:30 UTC)
- **Project manager report:** the "Prototype: converting" toast appeared, but no result dialog followed.
- **Likely causes** (can't be reproduced here without a device):
  - A long run with no visible feedback.
  - Or a codec that stopped responding: the converter's MediaCodec loops had no time limit, so a stall would hang forever.
- **Changes** (prior versions in `version-control/androidApp/2026-11-04_1929/`):
  - `PrototypeConvert.kt`:
    - A progress dialog opens at once. It shows format, "song N of M", %, song title, elapsed time and a **Cancel** button, and is replaced by the result dialog.
    - Any unexpected error is reported in the dialog.
    - If the screen was closed meanwhile, a toast says it's finished.
  - `MediaCodecConverter.kt`: each decoder/encoder wait loop now gives up after about 30 s without progress, with an error naming the song ("the decoder stopped responding", "the encoder never finished", …). It also refuses an encoder input buffer too small to hold one frame, instead of looping.
- `:androidApp:compileDebugKotlin` passes.
- **New dev APK:** `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_1930/` (all checks passed).

## Fix: far too slow on the phone (19:40 UTC)
- **Project manager report:** the run sat at "10%" on the first song. The 10% was the whole FLAC's progress, so song 1 of 10 was nearly done, but it was very slow.
- **Cause:** the converter waited up to 10 ms on every MediaCodec call and handled one buffer per wait, which caps speed at roughly real time.
- **Changes** (prior versions in `version-control/androidApp/2026-11-04_1939/`):
  - `MediaCodecConverter.kt`: feeds every free decoder input and takes every ready output without waiting, and pauses 2 ms only when nothing is ready. The encoder side does the same (drains output without waiting mid-stream). The stall checks are now time-based (30 s with no progress).
  - `PrototypeConvert.kt`: tests the playlist's **first 3 songs** (was 10; enough to prove joining, resampling and marks). Progress shows the song's own %, the overall %, and an estimated time left.
- **New dev APK:** `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_1940/` (all checks passed; new code confirmed in the APK).

## Phone results so far, and a diagnostic build (20:28 UTC)
- **Project manager's run** of the slower 19:30 build (10 songs, both formats):
  - **FLAC:** converted, checked and saved to Music/Exports; it plays the songs correctly on the phone.
  - **M4A:** the app restarted partway through and the dialog disappeared. The cause is unknown: a native crash in Android's AAC encoder / MP4 writer, or the screen being recreated.
  - The elapsed clock froze during "checking the file". That was a display flaw only (it updated only on progress reports).
- **Changes** (prior versions in `version-control/androidApp/2026-11-04_2026/`):
  - `PrototypeConvert.kt`:
    - Long-press now asks **FLAC / M4A / Both**, so M4A can be tested on its own.
    - The clock ticks every second, and the checking step shows its own %.
    - **Step log:** every step is appended and synced to `Download/RecordPlayer prototype log.txt` as it happens, so it survives the app being killed. The log is created through MediaStore Downloads (Android 10+, no permission); on 8–9 it goes in the app's own files folder.
    - The log also captures an app-code crash and its stack trace before the app closes. A native crash in Android's codec can't be caught, but the last log line then shows the step it died in.
  - `MediaCodecConverter.kt`: logs each step (song opened with its format, decoder output format and whether it resamples, encoder name, MP4 writer start/stop, frames per song, finish, song marks, failures).
- **New dev APK:** `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_2028/` (all checks passed; new code confirmed in the APK).

## Phone result: M4A on its own (2028 build)
- **Project manager's run** (Android 13; playlist "Test", 2 MP3s: one at 44.1 kHz, one at 48 kHz resampled to 44.1 kHz; M4A only):
  - Converted, checked and saved to `Music/Exports/Test.m4a`, with no restart. It took 75 s: 46.5 s to convert and 29 s to check.
  - **Length is exact:** 9,348,480 + 6,629,818 song frames + 44,100 gap frames = 16,022,398, which equals what the encoder received. Decoding back gives 16,024,576, which is +2,178. That is the AAC lead-in plus padding to the last 1,024-sample frame, as expected.
- **Still unexplained:** the earlier restart (old 19:30 build, 10 songs, FLAC then M4A). The next check is "Both" on a playlist of 3 or more songs, to see whether it only happens after a FLAC run or with longer runs.
- **Note for the full build:** the step log names songs by their URL-encoded file name (e.g. `%20`). That affects the log only. The checking step decodes the whole file again (about 40% of the time), so the full build should check length from the file's header instead.

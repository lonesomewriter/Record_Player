# Convert to Audio: full build

**Time:** 2026-11-04, 22:40 UTC – paused at the session limit – finished 2026-11-05, 13:50 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "can we properly implement it's popup now? (And remove the 'soon' marking from the button?) So people can close the progress popup and have it be sent to the Inbox?" after confirming FLAC and M4A both work on the phone.

**Backups:** `version-control/{shared,androidApp,desktopApp}/2026-11-04_2240/`. They include the prototype's `PrototypeConvert.kt`, which has been deleted.

## What changed
- **Shared:**
  - `convert/AudioConversion.kt`: format labels, mark notes and size estimates; `ConvertRules` (gap parsing, file names, estimate, free space); `ConvertOptions` / `ConvertJob` / `ConvertSaved`; Even out flag; measuring-pass progress.
  - `convert/Loudness.kt`: Even out volume. Each song is measured (RMS and peak) and brought to about −18 dBFS. The boost is capped at +12 dB, and a song is never raised past 98% of full scale, so it never clips.
  - `OggChapters` in `SongMarks.kt`: OGG Vorbis/Opus chapter tags (`CHAPTER001=…`, `CHAPTER001NAME=…`) plus title and album. The header pages are rebuilt and the later pages renumbered with fresh checksums.
  - `AppModel`: `ConvertTask` (running → done / cancelled / failed), `startConversion` (one job at a time, shown as running at once), `cancelConversion`, `dismissConversion`, and `LibraryPlatform` hooks.
  - `ui/ConvertToAudio.kt`:
    - the dialog (format list with unavailable formats greyed out and why; gap − [n] s +; Even out volume; file name; Save to with Change / Choose…; marks note; estimate and "Converted on this device")
    - the progress popup; closing it shows "Still converting — it's in your Inbox"
    - the Inbox card
  - `Playlists.kt`: the "Convert to Audio" button (the SOON tag and the prototype long-press removed).
  - The phone Inbox card.
  - Desktop: an Inbox view, a sidebar item with a % badge, and an Inbox icon.
- **Desktop:**
  - `GstConverter`: Even out volume (GStreamer `volume`), a per-format encoder check (M4A greyed out without `gstreamer1.0-libav`), and OGG/Opus marks.
  - `DesktopConvert`: `~/Music/Exports` (a taken name gets " (2)"), a free-space check, and writing to a hidden `.part` file renamed only when finished.
  - `main.kt`: the Choose… save dialog, which asks before replacing a file.
- **Android:**
  - `MediaCodecConverter`: Even out volume, and Opus (Android 10+, OGG; hidden below 10).
  - `AndroidConvertPlatform`: the file is made in the app's cache, then copied to Music/Exports (media store) or the file chosen with Change. Cancelling or failing removes both.
  - `ConvertService`: a foreground service with a progress notification and Cancel, holding a wake lock only while converting. Registered in the manifest; no new permission.
  - `MainActivity`: the picker and the service start. `RecordDiscApplication` uses the converting platform.
  - **Removed:** `PrototypeConvert.kt`, the `prototypeConvert` hook and its MainActivity wiring.
- **Fixed during testing:** a cancel arriving just as the conversion finished could still save the file. Both platforms now check once more before saving.

## Tests
- **New** `ConvertRulesTest` (7): gap rules, file names, estimate, two-pass progress, gain maths, 16-bit clamping, the meter.
- **New** `ConvertTaskTest` (2): running → done with the song title and %, one job at a time, Dismiss; cancel; failure message.
- **`GstConverterTest`, now 10:**
  - OGG and Opus carry chapter tags and still decode in full (the rewritten pages are valid)
  - Even out brings a quiet and a loud song to the target level (±10%), with the measuring pass first
  - `.part` and cancel leave no file, and low free space is refused
  - taken names get a number
- **New** `DesktopScreenshotTest.convertToAudioDialogProgressAndInbox`: the button (no "To audio file"), the dialog, the gap stepper, Convert → progress "Song 2 of 4 · 40%", closing → toast, the Inbox card and 40% badge, Done → "Saved to …", Show in folder, and Dismiss. It replaces `prototypeLongPressReachesTheHook`.
  - Screenshots: `shared/build/screenshots/desktop/46-convert-dialog.png`, `47-convert-progress.png`, `48-desktop-inbox-converting.png`.
- **Passed:** `OpenFileTest`, `SingleInstanceTest`, and the `songMenus…`, `playlistDialogs` and `anOpenedOutsideFile…` screen tests.
- **`AppModelTest`:** run alone twice, each run had one failure, from the already-known flaky pair (`resumeOnStart…` / `playbackModes…`, confirmed flaky on a clean checkout). In a combined run, `savedTagsUpdate…` and `bundleImport…` also timed out once, then passed in both solo runs. They look load-related, not caused by this change.

## Builds
`scripts/build.sh dev` passed all checks:
- `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-05_1347/`
- `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-05_1347/`

`desktop-file-validate` was skipped (not installed); the menu entry is unchanged since it last passed.

## Not checked here (no phone or desktop session)
A real conversion on the phone in each format, Even out volume, closing the popup, Cancel from the notification, Change → picker, and Opus playback; desktop Choose… and Show in folder.

## Not included (follow-up)
Cover art inside the output file.

## Impacts
No new dependencies, permissions or network use. Not committed.
